# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# FlightInfo - update_github.ps1
# Version 1.2
# Purpose : One-step publisher. Takes a project zip (as delivered), mirrors
#           its contents into a local clone of the GitHub repository
#           (including hidden folders such as .github and deletions),
#           commits and pushes. GitHub Actions then builds the APK.
#           Optionally creates a version tag so a GitHub Release is made.
# Runs on : Windows 10/11, PowerShell 5.1+, Git for Windows installed.
#           First push opens a browser window to sign in (Git Credential
#           Manager); later pushes are silent.
# Usage   : double-click update_github.bat, or drag a zip onto it, or
#           powershell -File update_github.ps1 -ZipPath C:\path\x.zip [-Tag]
# Encoding: UTF-8 without BOM, CRLF or LF both accepted by PowerShell
# =============================================================

param(
    [string]$ZipPath = "",
    [string]$Message = "",
    [switch]$Tag
)

# =============================================================
# Parameters
# =============================================================
$RepoUrl        = "https://github.com/eldadgalker-dev/FlightInfo.git"   # remote repository
$Branch         = "main"                                              # branch that triggers the build
$LocalDir       = Join-Path $env:USERPROFILE "FlightInfo-repo"        # local clone location
$GitUserName    = "Eldad Galker"                                      # commit author
$GitUserEmail   = "eldad@galker.com"
$ProjectMarker  = "settings.gradle.kts"     # file that identifies the project root inside the zip
$VersionFile    = "app\build.gradle.kts"    # file to read versionName from
$ExcludeDirs    = @(".git", ".gradle", "build")   # never copied or deleted by the mirror

# =============================================================
# Validation and helpers
# =============================================================
# "Continue": PowerShell 5.1 turns redirected stderr of native commands (git
# writes progress there) into terminating errors under "Stop". Exit codes are
# checked explicitly instead.
$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

function Run-Git {
    # Run git with arguments, stream output, fail on non-zero exit.
    param([string[]]$GitArgs)
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) { Fail "git $($GitArgs -join ' ') failed (exit $LASTEXITCODE)" }
}

# -- Git present? --
$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCmd) {
    Start-Process "https://git-scm.com/download/win"
    Fail "Git for Windows is not installed. The download page has been opened. Install with default options, then run this tool again."
}

# -- Zip path: argument, or file dialog --
if (-not $ZipPath) {
    Add-Type -AssemblyName System.Windows.Forms
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Title = "Select the FlightInfo project zip"
    $dlg.Filter = "Zip files (*.zip)|*.zip"
    $dlg.InitialDirectory = [Environment]::GetFolderPath("MyDocuments")
    if ($dlg.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
    $ZipPath = $dlg.FileName
}
if (-not (Test-Path -LiteralPath $ZipPath)) { Fail "Zip not found: $ZipPath" }

# =============================================================
# 1. Local clone: create or refresh
# =============================================================
Step "Preparing local repository at $LocalDir"
$isRepo = $false
if (Test-Path -LiteralPath $LocalDir) {
    Push-Location $LocalDir
    & git rev-parse --is-inside-work-tree *> $null
    $isRepo = ($LASTEXITCODE -eq 0)
    Pop-Location
}
if (-not $isRepo) {
    if ((Test-Path -LiteralPath $LocalDir) -and (Get-ChildItem -LiteralPath $LocalDir -Force | Select-Object -First 1)) {
        Fail "The folder $LocalDir exists but is not a Git repository (probably left over from an interrupted first run). Delete that folder and run the tool again."
    }
    New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null
    Write-Host "Cloning $RepoUrl (a browser sign-in window may open)..."
    Run-Git @("clone", "--branch", $Branch, $RepoUrl, $LocalDir)
}
Set-Location $LocalDir
$origin = ((& git remote get-url origin) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $origin) { Fail "Could not read the remote URL of $LocalDir. Delete the folder and run again." }
if ($origin -ne $RepoUrl) { Fail "Local folder points to a different repository ($origin). Delete $LocalDir or fix RepoUrl." }

Run-Git @("config", "user.name", $GitUserName)
Run-Git @("config", "user.email", $GitUserEmail)
Run-Git @("fetch", "origin", $Branch)
Run-Git @("checkout", $Branch)
Run-Git @("reset", "--hard", "origin/$Branch")   # local clone is a mirror; remote state wins

# =============================================================
# 2. Extract the zip and locate the project root
# =============================================================
Step "Extracting $ZipPath"
$tmp = Join-Path $env:TEMP ("flightinfo_" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp | Out-Null
Expand-Archive -LiteralPath $ZipPath -DestinationPath $tmp -Force

$src = $tmp
if (-not (Test-Path (Join-Path $src $ProjectMarker))) {
    $candidates = Get-ChildItem -Path $tmp -Directory | Where-Object { Test-Path (Join-Path $_.FullName $ProjectMarker) }
    if ($candidates.Count -ne 1) { Fail "Could not find $ProjectMarker in the zip (expected at root or in exactly one sub-folder)." }
    $src = $candidates[0].FullName
}
Write-Host "Project root: $src"

# =============================================================
# 3. Mirror the extracted project into the clone
# =============================================================
Step "Mirroring files into the repository"
$xd = @()
foreach ($d in $ExcludeDirs) { $xd += (Join-Path $LocalDir $d); $xd += (Join-Path $src $d) }
& robocopy $src $LocalDir /MIR /NFL /NDL /NJH /NJS /NP /R:1 /W:1 /XD @xd | Out-Null
if ($LASTEXITCODE -ge 8) { Fail "robocopy failed with exit code $LASTEXITCODE" }
Remove-Item -Recurse -Force $tmp

# =============================================================
# 4. Commit and push
# =============================================================
$version = "unknown"
$vf = Join-Path $LocalDir $VersionFile
if (Test-Path $vf) {
    $m = Select-String -Path $vf -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if ($m) { $version = $m.Matches[0].Groups[1].Value }
}
if (-not $Message) { $Message = "FlightInfo $version" }

Step "Committing"
Run-Git @("add", "-A")
$status = (& git status --porcelain)
if (-not $status) {
    Write-Host "Nothing changed: the repository already matches the zip." -ForegroundColor Yellow
} else {
    Write-Host ($status | Out-String)
    Run-Git @("commit", "-m", $Message)
    Step "Pushing to $Branch (a browser sign-in window may open the first time)"
    Run-Git @("push", "origin", $Branch)
}

if ($Tag) {
    $tagName = "v$version"
    Step "Tagging $tagName (creates a GitHub Release with the APK)"
    $exists = (& git tag -l $tagName)
    if ($exists) { Write-Host "Tag $tagName already exists; skipping." -ForegroundColor Yellow }
    else { Run-Git @("tag", $tagName); Run-Git @("push", "origin", $tagName) }
}

$actions = $RepoUrl -replace "\.git$", "" 
Write-Host ""
Write-Host "Done. Build progress: $actions/actions" -ForegroundColor Green
Write-Host "The APK appears under the run's Artifacts as FlightInfo-apk (about 8-15 minutes)."
Start-Process "$actions/actions"
Read-Host "Press Enter to close"
