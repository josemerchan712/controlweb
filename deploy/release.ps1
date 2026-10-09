<#
.SYNOPSIS
  Builds the ControlWeb production image on THIS PC from a clean copy of a pushed commit and deploys it to the VPS.

.DESCRIPTION
  The VPS never compiles anything (3.7 GiB of RAM, no swap, shared with Rotten Odds). This script:
    1. checks that the working tree is clean, that you are on main, and that HEAD is pushed;
    2. builds the image from a throw-away git worktree of that commit (so no local file can slip in);
    3. verifies the image (deploy/verify-image.sh through Git Bash);
    4. saves it to a FILE (never through a pipe: in Windows PowerShell 5.1 a pipe between programs turns binary data into text
       and corrupts it), computes its SHA-256 and uploads both with scp;
    5. runs deploy/deploy.sh on the server, which checks the checksum, backs up the database, starts the new release and rolls
       back by itself if it does not become healthy.
  Nothing secret passes through here: the secrets live in /opt/controlweb/.env.prod on the server.

  Before you run it: look at GitHub Actions and make sure CI is GREEN for the commit (this script cannot see a private
  repository), then pass -CiChecked.

.PARAMETER SshTarget
  user@host or an alias of ~/.ssh/config. Default: the environment variable CONTROLWEB_SSH. Key authentication only.

.PARAMETER Rollback
  Does not build anything: asks the server to go back to the previous release (deploy.sh rollback).

.PARAMETER Status
  Shows what runs on the server and what a rollback would go back to.

.PARAMETER DryRun
  Prints what would be done without building, uploading or touching the server. Checks that depend on the state of the
  repository only warn.

.EXAMPLE
  .\deploy\release.ps1 -SshTarget jose@my-vps -CiChecked

.EXAMPLE
  .\deploy\release.ps1 -SshTarget jose@my-vps -Rollback
#>
[CmdletBinding()]
param(
    [string] $SshTarget = $env:CONTROLWEB_SSH,
    [string] $RemoteDir = 'controlweb',
    [string] $ReleasesDir = 'controlweb-releases',
    [switch] $CiChecked,
    [switch] $SkipVerifyImage,
    [switch] $Rollback,
    [switch] $Status,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$SshOptions = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15')

function Write-Step([string] $Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Dry([string] $Message) { Write-Host "    [dry-run] $Message" -ForegroundColor DarkGray }

# Runs a program and stops if it fails. In a dry run it only prints it.
function Invoke-Native([string] $Description, [string] $File, [string[]] $Arguments) {
    Write-Step $Description
    if ($DryRun) { Write-Dry "$File $($Arguments -join ' ')"; return }
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Description failed (exit code $LASTEXITCODE)." }
}

# Read-only git questions: always executed, even in a dry run.
function Get-Git([string[]] $Arguments) {
    $output = & git -C $RepoRoot @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed." }
    return $output
}

# A check that stops the release, or only warns in a dry run.
function Assert-Condition([bool] $Ok, [string] $Message) {
    if ($Ok) { return }
    if ($DryRun) { Write-Warning "$Message (a real run would stop here)"; return }
    throw $Message
}

function Invoke-Ssh([string] $Description, [string] $RemoteCommand) {
    Invoke-Native $Description 'ssh' ($SshOptions + @($SshTarget, $RemoteCommand))
}

try {
    if ([string]::IsNullOrWhiteSpace($SshTarget)) {
        throw 'Say where to deploy: -SshTarget user@host (or set the environment variable CONTROLWEB_SSH).'
    }
    if ($SshTarget -notmatch '^[A-Za-z0-9_.@:-]+$') { throw 'SshTarget has unexpected characters.' }

    if ($Status) { Invoke-Ssh 'Server status' "$RemoteDir/deploy/deploy.sh status"; return }
    if ($Rollback) {
        Invoke-Ssh 'Rolling back on the server' "$RemoteDir/deploy/deploy.sh rollback"
        return
    }

    # ---- 1. what is being released ----
    Write-Step 'Checking the repository'
    $dirty = @(Get-Git @('status', '--porcelain'))
    Assert-Condition ($dirty.Count -eq 0) 'The working tree has uncommitted or untracked changes: commit or remove them first.'
    $branch = (Get-Git @('rev-parse', '--abbrev-ref', 'HEAD')) | Select-Object -First 1
    Assert-Condition ($branch -eq 'main') "Releases are built from main, but you are on '$branch'."
    if (-not $DryRun) { Get-Git @('fetch', '--quiet', 'origin') | Out-Null }
    $head = (Get-Git @('rev-parse', 'HEAD')) | Select-Object -First 1
    $remote = (Get-Git @('rev-parse', 'origin/main')) | Select-Object -First 1
    Assert-Condition ($head -eq $remote) 'HEAD is not the same as origin/main: push first (the server fetches the commit from GitHub).'
    $sha = (Get-Git @('rev-parse', '--short=12', 'HEAD')) | Select-Object -First 1
    Assert-Condition ($CiChecked.IsPresent) "Check that CI is green for commit $sha in GitHub Actions, then run again with -CiChecked."
    Write-Host "    commit $sha"

    $image = "controlweb-app:$sha"
    $work = Join-Path $env:TEMP "controlweb-release-$sha"
    $source = Join-Path $work 'src'
    $tar = Join-Path $work "controlweb-app-$sha.tar"
    $remoteTar = "$ReleasesDir/controlweb-app-$sha.tar"

    # ---- 2. build from a clean copy of the commit ----
    Invoke-Native 'Docker is running' 'docker' @('version', '--format', '{{.Server.Version}}')
    if (-not $DryRun) {
        if (Test-Path $work) { Remove-Item -Recurse -Force $work }
        New-Item -ItemType Directory -Path $work | Out-Null
    }
    Invoke-Native "Checking out a clean copy of $sha" 'git' @('-C', $RepoRoot, 'worktree', 'add', '--detach', $source, $sha)
    try {
        Invoke-Native "Building $image (one build at a time; first time 3-6 minutes)" 'docker' @('build', '--tag', $image, $source)

        if (-not $SkipVerifyImage) {
            $bash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
            if ($DryRun -or (Test-Path $bash)) {
                Invoke-Native 'Verifying what is inside the image' $bash @('-c', "cd '$($source -replace '\\', '/')' && bash deploy/verify-image.sh $image")
            }
            else {
                Write-Warning 'Git Bash was not found: the image was NOT verified (deploy/verify-image.sh).'
            }
        }

        # ---- 3. save to a file (never a pipe), checksum, upload ----
        Invoke-Native 'Saving the image to a file' 'docker' @('save', '-o', $tar, $image)
        $checksumFile = "$tar.sha256"
        if ($DryRun) {
            Write-Dry "SHA-256 of $tar -> $checksumFile"
        }
        else {
            $hash = (Get-FileHash -Algorithm SHA256 -Path $tar).Hash.ToLowerInvariant()
            $line = "$hash  $(Split-Path -Leaf $tar)`n"
            [System.IO.File]::WriteAllText($checksumFile, $line, (New-Object System.Text.UTF8Encoding($false)))
            Write-Host "    sha256 $hash"
        }
        Invoke-Ssh 'Preparing the releases folder on the server' "mkdir -p $ReleasesDir"
        Invoke-Native 'Uploading the image and its checksum' 'scp' ($SshOptions + @($tar, $checksumFile, "${SshTarget}:$ReleasesDir/"))

        # ---- 4. deploy on the server ----
        Invoke-Ssh "Deploying $sha on the server (backup, start, wait for health; automatic rollback)" "$RemoteDir/deploy/deploy.sh $sha $remoteTar"
        Write-Host ''
        Write-Host "Release $sha is live. To go back:  .\deploy\release.ps1 -SshTarget $SshTarget -Rollback" -ForegroundColor Green
    }
    finally {
        if (-not $DryRun) {
            try { & git -C $RepoRoot worktree remove --force $source 2>$null } catch { Write-Warning "Could not remove the temporary worktree $source" }
            if (Test-Path $work) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
        }
    }
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
