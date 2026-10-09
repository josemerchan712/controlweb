<#
.SYNOPSIS
  Runs the tests of the shell scripts on LINUX (an Ubuntu container with JDK 21), the way the GitHub CI does. Windows hides a whole family
  of mistakes (no execute bit, line endings, the order of a HashSet): a test class of scripts that has only run on Windows is NOT tested.

.DESCRIPTION
  1. builds the source tree to test: by default what `git add -A` would commit (a temporary index; your real index is not touched);
     -Staged = exactly what is staged (used by the pre-commit hook); -Commit <ref> = that commit;
  2. makes a tar with `git archive` (file modes as stored in git, LF line endings, written to a FILE: no binary data through a pipe);
  3. runs deploy/linux-test/run-tests.sh in the container: maven test for the selected classes, then a table per class. The classes that
     ci.yml lists in "Check that the script tests really ran here" must run with NO skipped test, exactly as the CI requires.

  By default it runs every test class in a `deploy` test package plus CiWorkflowTest and ShellcheckGuardianTest. Exit code 0 = "LINUX OK".
  The Maven cache is a Docker volume (controlweb-linux-m2) seeded once from your ~\.m2 so that it works offline.

.PARAMETER Tests
  Comma-separated test class names instead of the default list.
.PARAMETER Rebuild
  Rebuilds the Docker image (deploy/linux-test/Dockerfile).
#>
[CmdletBinding()]
param(
    [switch] $Staged,
    [string] $Commit,
    [string] $Tests,
    [switch] $Rebuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Image = 'controlweb-linux-test'
$Volume = 'controlweb-linux-m2'

# Runs a native probe whose failure is an answer, not an error. (In Windows PowerShell 5.1, merging a native command's stderr into
# its output turns every stderr line into a terminating error under $ErrorActionPreference = 'Stop'; discarding it with $null does not.)
function Test-Native([scriptblock] $Block) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block 2>$null | Out-Null } finally { $ErrorActionPreference = $previous }
    return ($LASTEXITCODE -eq 0)
}

function Invoke-Checked([string] $What, [scriptblock] $Block) {
    & $Block
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)." }
}

if (-not (Test-Native { docker info --format '{{.ServerVersion}}' })) { throw 'Docker is not running: start Docker Desktop.' }

# ---- image ----
if ($Rebuild -or -not (Test-Native { docker image inspect $Image })) {
    Write-Host "==> building the Linux test image ($Image)" -ForegroundColor Cyan
    Invoke-Checked 'docker build' { docker build -q -t $Image (Join-Path $PSScriptRoot 'linux-test') }
}

# ---- the tree to test ----
if ($Commit) { $tree = (& git -C $Repo rev-parse "$Commit^{tree}").Trim() }
elseif ($Staged) { $tree = (& git -C $Repo write-tree).Trim() }
else {
    $index = Join-Path $env:TEMP ('controlweb-index-' + [guid]::NewGuid().ToString('N'))
    $env:GIT_INDEX_FILE = $index
    try {
        & git -C $Repo read-tree HEAD
        & git -C $Repo add -A
        $tree = (& git -C $Repo write-tree).Trim()
    }
    finally { Remove-Item Env:\GIT_INDEX_FILE -ErrorAction SilentlyContinue; Remove-Item $index -Force -ErrorAction SilentlyContinue }
}
if ($LASTEXITCODE -ne 0 -or -not $tree) { throw 'could not build the tree to test.' }

$work = Join-Path $env:TEMP ('controlweb-linux-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    Invoke-Checked 'git archive' { git -C $Repo -c core.autocrlf=false archive --format=tar "--output=$work\src.tar" $tree }

    # ---- which classes ----
    if (-not $Tests) {
        $names = @(Get-ChildItem (Join-Path $Repo 'src\test\java') -Recurse -Filter '*Test.java' |
            Where-Object { $_.FullName -match '\\deploy\\' } | ForEach-Object { $_.BaseName }) + @('CiWorkflowTest', 'ShellcheckGuardianTest')
        $Tests = ($names | Sort-Object -Unique) -join ','
    }
    $ci = [System.IO.File]::ReadAllText((Join-Path $Repo '.github\workflows\ci.yml'))
    $m = [regex]::Match($ci, 'for test in ([A-Za-z ]+); do')
    $mustRun = if ($m.Success) { ($m.Groups[1].Value.Trim() -split '\s+') -join ',' } else { '' }

    # ---- Maven cache volume (seeded once from the Windows cache so that it works offline) ----
    $mode = 'online'
    if (-not (Test-Native { docker volume inspect $Volume })) {
        Invoke-Checked 'docker volume create' { docker volume create $Volume | Out-Null }
        $windowsCache = Join-Path $env:USERPROFILE '.m2'
        if (Test-Path $windowsCache) {
            Write-Host "==> seeding the Maven cache volume from $windowsCache (once)" -ForegroundColor Cyan
            Invoke-Checked 'seeding the Maven cache' { docker run --rm --user 0 -v "${Volume}:/home/runner/.m2" -v "${windowsCache}:/src:ro" $Image sh -c 'cp -r /src/. /home/runner/.m2/ && chown -R 1001:1001 /home/runner/.m2' }
        }
    }
    if (Test-Native { docker run --rm -v "${Volume}:/home/runner/.m2" $Image test -d /home/runner/.m2/repository }) { $mode = 'offline' }

    Write-Host "==> testing tree $($tree.Substring(0, 10)) on Linux ($mode): $Tests" -ForegroundColor Cyan
    & docker run --rm -v "${Volume}:/home/runner/.m2" -v "${work}:/in:ro" -v "$(Join-Path $PSScriptRoot 'linux-test'):/runner:ro" $Image bash /runner/run-tests.sh $Tests $mustRun $mode
    $status = $LASTEXITCODE
}
finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }

if ($status -ne 0) { Write-Host 'These tests FAIL on Linux. Do not commit: CI would be red.' -ForegroundColor Red }
exit $status
