<#
.SYNOPSIS
  Downloads the encrypted ControlWeb database backups from the VPS to THIS PC, checks them, and can prove on this PC that a
  stored copy decrypts and restores (the one test the server cannot do: it has no private key).

.DESCRIPTION
  Windows PowerShell 5.1. Nothing binary ever goes through a PowerShell pipe (5.1 turns bytes into text and corrupts them) and
  ssh never writes with PowerShell's ">": every transfer is done by cmd.exe with a plain file redirection
  ("ssh ... < request.txt > copy.partial"), which is byte-exact.

  The download uses ONE restricted SSH key (authorized_keys on the server: restrict,command="sudo -n .../serve-backup.sh"). That
  key can only ask three things: "list", "sha256 <name>" and "get <name>" for files that look like controlweb-<stamp>.dump.age.
  It cannot get a shell, cannot write, cannot read anything else. The copies are encrypted with age: this script never needs
  the private key, except for -VerifyRestore.

  For every copy that this PC does not have yet, it: downloads to a ".partial" file; compares the size with the server's list;
  compares the SHA-256 with the one the server computes; checks the age header; and only then renames it. A failed copy is
  deleted and the run ends with an error. Old local copies are rotated (-Keep) only after a fully successful run.

  Exit codes: 0 all good - 1 failure - 3 the newest copy on the server is older than -MaxAgeHours (the server stopped backing up).

.PARAMETER SshTarget
  cwbackup@host (the restricted account). Default: environment variable CONTROLWEB_BACKUP_SSH.

.PARAMETER KeyFile
  The private half of the restricted key. Default: ~\.ssh\controlweb_backup_ed25519. It must be readable only by you.

.PARAMETER Destination
  Folder for the copies, the log and the status files. Default: ~\controlweb-backups.

.PARAMETER List
  Only lists the copies stored on the server.

.PARAMETER Status
  Shows the newest local copy, the last run and the scheduled task, and exits 1 if the newest copy is too old.

.PARAMETER VerifyRestore
  Decrypts a local copy with -Identity (age private key) into a temporary folder and restores it into a throw-away PostgreSQL
  container in your local Docker; checks the tables; deletes the container and the decrypted file. Newest copy unless -CopyName.

.PARAMETER InstallTask
  Registers the daily Windows scheduled task (-TaskTime). -RemoveTask removes it.

.PARAMETER DryRun
  Prints what would be done; downloads, restores and registers nothing.
#>
[CmdletBinding()]
param(
    [string] $SshTarget = $env:CONTROLWEB_BACKUP_SSH,
    [string] $KeyFile = (Join-Path $env:USERPROFILE '.ssh\controlweb_backup_ed25519'),
    [string] $Destination = (Join-Path $env:USERPROFILE 'controlweb-backups'),
    [int] $Keep = 30,
    [int] $MaxAgeHours = 36,
    [switch] $List,
    [switch] $Status,
    [switch] $VerifyRestore,
    [string] $Identity,
    [string] $CopyName,
    [string] $PostgresImage,
    [switch] $InstallTask,
    [switch] $RemoveTask,
    [string] $TaskTime = '10:00',
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$TaskName = 'ControlWeb backup download'
$NamePattern = '^controlweb-\d{8}T\d{6}Z\.dump\.age$'
$AgeHeader = 'age-encryption.org/v1'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$LogFile = Join-Path $Destination 'download.log'
# Test hooks: stand-ins for docker and age (the tests cannot rely on what is installed on the machine that runs them).
$DockerExe = 'docker'
if ($env:CONTROLWEB_DOCKER_EXE) { $DockerExe = $env:CONTROLWEB_DOCKER_EXE }

function Write-Log([string] $Message, [string] $Color = 'Gray') {
    $line = '{0} {1}' -f (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'), $Message
    Write-Host $line -ForegroundColor $Color
    if (-not $DryRun -and (Test-Path -LiteralPath $Destination)) {
        Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
    }
}
function Write-Dry([string] $Message) { Write-Host "    [dry-run] $Message" -ForegroundColor DarkGray }

# ---- ssh through cmd.exe: stdin and stdout are FILES, so the bytes are never touched ------------------------------------------
function Get-SshExe {
    if ($env:CONTROLWEB_SSH_EXE) { return $env:CONTROLWEB_SSH_EXE }   # test hook: a stand-in for ssh.exe
    $windowsSsh = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe'
    if (Test-Path -LiteralPath $windowsSsh) { return $windowsSsh }
    $found = Get-Command ssh.exe -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    throw 'ssh.exe was not found (Windows optional feature "OpenSSH Client", or Git for Windows).'
}

# Sends one request line to the server and leaves the answer in $OutFile. Throws with the server's message on failure.
function Invoke-Remote([string] $Request, [string] $OutFile) {
    $work = Join-Path $env:TEMP ('controlweb-req-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $requestFile = Join-Path $work 'request.txt'
        $errorFile = Join-Path $work 'stderr.txt'
        [System.IO.File]::WriteAllText($requestFile, $Request + "`n", $Utf8NoBom)
        $ssh = Get-SshExe
        $line = '"{0}" -T -i "{1}" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 {2} < "{3}" > "{4}" 2> "{5}"' -f `
            $ssh, $KeyFile, $SshTarget, $requestFile, $OutFile, $errorFile
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $env:ComSpec
        $info.Arguments = '/s /c "' + $line + '"'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $process = [System.Diagnostics.Process]::Start($info)
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            $detail = ''
            if (Test-Path -LiteralPath $errorFile) { $detail = ([System.IO.File]::ReadAllText($errorFile).Trim() -replace '\s+', ' ') }
            if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) }
            throw "the request '$($Request.Split(' ')[0])' failed (exit code $($process.ExitCode)): $detail"
        }
    }
    finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-RemoteText([string] $Request) {
    $out = Join-Path $env:TEMP ('controlweb-ans-' + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        Invoke-Remote $Request $out
        return [System.IO.File]::ReadAllText($out)
    }
    finally { Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue }
}

# [pscustomobject] list of @{ Name; Bytes } newest first. The names are validated again here: the server is not trusted blindly.
function Get-RemoteCopies {
    $copies = @()
    foreach ($line in ((Get-RemoteText 'list') -split "`n")) {
        $line = $line.Trim()
        if ($line -eq '') { continue }
        $parts = $line -split ' '
        if ($parts.Count -ne 2 -or $parts[0] -notmatch $NamePattern -or $parts[1] -notmatch '^\d+$') { throw "unexpected line in the server's list: '$line'" }
        $copies += [pscustomobject]@{ Name = $parts[0]; Bytes = [int64] $parts[1] }
    }
    return $copies
}

function Get-StampTime([string] $Name) {
    $m = [regex]::Match($Name, '^controlweb-(\d{8}T\d{6}Z)\.dump\.age$')
    return [datetime]::ParseExact($m.Groups[1].Value, "yyyyMMdd'T'HHmmss'Z'", [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::AssumeUniversal -bor [System.Globalization.DateTimeStyles]::AdjustToUniversal)
}

function Get-LocalCopies {
    if (-not (Test-Path -LiteralPath $Destination)) { return @() }
    return @(Get-ChildItem -LiteralPath $Destination -File | Where-Object { $_.Name -match $NamePattern } | Sort-Object Name -Descending)
}

function Test-AgeHeader([string] $Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $buffer = New-Object byte[] 21
        $read = $stream.Read($buffer, 0, 21)
        return ($read -eq 21 -and [System.Text.Encoding]::ASCII.GetString($buffer) -eq $AgeHeader)
    }
    finally { $stream.Dispose() }
}

function Set-StatusFile([string] $Name, [string] $Content) {
    [System.IO.File]::WriteAllText((Join-Path $Destination $Name), $Content + "`r`n", $Utf8NoBom)
}

# ---- download ----------------------------------------------------------------------------------------------------------------
function Invoke-Download {
    if (-not $SshTarget) { throw 'No -SshTarget (and no CONTROLWEB_BACKUP_SSH): use cwbackup@host.' }
    if (-not (Test-Path -LiteralPath $KeyFile)) { throw "The key file $KeyFile does not exist (docs/DESPLIEGUE.md, 'La clave SSH restringida')." }
    if ($DryRun) {
        Write-Dry "ssh ${SshTarget}: list"
        Write-Dry "for each copy not in ${Destination}: get -> .partial, size check, sha256 check, age header, rename"
        Write-Dry "keep the newest $Keep local copies, write last-run.txt / last-failure.txt and download.log"
        return 0
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $lock = $null
    try {
        try { $lock = [System.IO.File]::Open((Join-Path $Destination '.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch { throw 'Another download is already running (locked: .lock).' }

        Get-ChildItem -LiteralPath $Destination -Filter '*.partial' -Force | Remove-Item -Force   # leftovers of an interrupted run
        $remote = @(Get-RemoteCopies)
        if ($remote.Count -eq 0) { throw 'The server has no copies at all.' }
        $downloaded = 0
        foreach ($copy in ($remote | Sort-Object Name)) {
            $final = Join-Path $Destination $copy.Name
            if ((Test-Path -LiteralPath $final) -and (Get-Item -LiteralPath $final).Length -eq $copy.Bytes) { continue }
            $partial = Join-Path $Destination ('.' + $copy.Name + '.partial')
            Write-Log "downloading $($copy.Name) ($($copy.Bytes) bytes)"
            try {
                Invoke-Remote ('get ' + $copy.Name) $partial
                $size = (Get-Item -LiteralPath $partial).Length
                if ($size -ne $copy.Bytes) { throw "size mismatch: the server says $($copy.Bytes) bytes, received $size" }
                $answer = (Get-RemoteText ('sha256 ' + $copy.Name)).Trim()
                $expected = ($answer -split '\s+')[0].ToLowerInvariant()
                if ($expected -notmatch '^[0-9a-f]{64}$') { throw "the server's checksum answer is not a SHA-256: '$answer'" }
                $actual = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($actual -ne $expected) { throw "checksum mismatch: the server has $expected, the download is $actual" }
                if (-not (Test-AgeHeader $partial)) { throw 'the file does not start with the age header: it is not an encrypted backup' }
                Move-Item -LiteralPath $partial -Destination $final -Force
                $downloaded++
            }
            catch {
                Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
                throw "copy $($copy.Name): $($_.Exception.Message)"
            }
        }
        # rotation only after everything worked
        $local = @(Get-LocalCopies)
        $removed = 0
        if ($local.Count -gt $Keep) {
            foreach ($old in ($local | Select-Object -Skip $Keep)) { Remove-Item -LiteralPath $old.FullName -Force; $removed++ }
        }
        $newest = $remote[0]
        $age = (Get-Date).ToUniversalTime() - (Get-StampTime $newest.Name)
        Set-StatusFile 'last-run.txt' ("{0} OK downloaded {1}, removed {2}, newest {3}" -f (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'), $downloaded, $removed, $newest.Name)
        Remove-Item -LiteralPath (Join-Path $Destination 'last-failure.txt') -Force -ErrorAction SilentlyContinue
        Write-Log "done: $downloaded new, $removed rotated out, $(@(Get-LocalCopies).Count) stored; newest on the server is $([math]::Round($age.TotalHours, 1)) h old" 'Green'
        if ($age.TotalHours -gt $MaxAgeHours) {
            $message = "the newest copy on the server ($($newest.Name)) is $([math]::Round($age.TotalHours, 1)) h old (limit $MaxAgeHours h): the server backup is not running"
            Set-StatusFile 'last-failure.txt' ((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') + ' STALE ' + $message)
            Write-Log "STALE: $message" 'Red'
            return 3
        }
        return 0
    }
    catch {
        $message = $_.Exception.Message
        Write-Log "FAILED: $message" 'Red'
        if (Test-Path -LiteralPath $Destination) { Set-StatusFile 'last-failure.txt' ((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') + ' FAILED ' + $message) }
        return 1
    }
    finally { if ($lock) { $lock.Dispose() } }
}

# ---- status ------------------------------------------------------------------------------------------------------------------
function Show-Status {
    $local = @(Get-LocalCopies)
    $exit = 0
    if ($local.Count -eq 0) { Write-Host 'No local copies yet.' -ForegroundColor Red; $exit = 1 }
    else {
        $age = (Get-Date).ToUniversalTime() - (Get-StampTime $local[0].Name)
        $color = 'Green'
        if ($age.TotalHours -gt $MaxAgeHours) { $color = 'Red'; $exit = 1 }
        Write-Host ("Newest local copy: {0} ({1} h old, limit {2} h); {3} stored in {4}" -f $local[0].Name, [math]::Round($age.TotalHours, 1), $MaxAgeHours, $local.Count, $Destination) -ForegroundColor $color
    }
    foreach ($name in @('last-run.txt', 'last-failure.txt')) {
        $path = Join-Path $Destination $name
        if (Test-Path -LiteralPath $path) { Write-Host ("{0}: {1}" -f $name, (Get-Content -LiteralPath $path -TotalCount 1)) }
    }
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task) {
        $info = Get-ScheduledTaskInfo -TaskName $TaskName
        Write-Host ("Scheduled task '{0}': {1}; last run {2}; last result {3} (0 = success); next run {4}" -f $TaskName, $task.State, $info.LastRunTime, $info.LastTaskResult, $info.NextRunTime)
    }
    else { Write-Host "Scheduled task '$TaskName' is NOT registered (-InstallTask)." -ForegroundColor Yellow }
    return $exit
}

# ---- scheduled task ----------------------------------------------------------------------------------------------------------
function Install-BackupTask {
    if (-not $SshTarget) { throw 'No -SshTarget: the task needs to know where to download from.' }
    if ($TaskTime -notmatch '^([01]\d|2[0-3]):[0-5]\d$') { throw "-TaskTime must be HH:mm (24 h), not '$TaskTime'." }
    $script = (Resolve-Path $PSCommandPath).Path
    $arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -SshTarget "{1}" -KeyFile "{2}" -Destination "{3}"' -f $script, $SshTarget, $KeyFile, $Destination
    if ($DryRun) {
        Write-Dry "register task '$TaskName': daily at $TaskTime, StartWhenAvailable, RunOnlyIfNetworkAvailable, runs on battery, 3 retries every 30 min, 30 min limit"
        Write-Dry "powershell.exe $arguments"
        return
    }
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -Daily -At $TaskTime
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
        -Description 'Downloads the encrypted ControlWeb backups (deploy/backup/download-backups.ps1).' -Force | Out-Null
    Write-Host "Registered '$TaskName': every day at $TaskTime. See it with: .\deploy\backup\download-backups.ps1 -Status" -ForegroundColor Green
}

# ---- local restore proof -----------------------------------------------------------------------------------------------------
function Get-ComposePostgresImage {
    if ($PostgresImage) { return $PostgresImage }
    $compose = Join-Path $RepoRoot 'compose.prod.yml'
    if (Test-Path -LiteralPath $compose) {
        $m = [regex]::Match([System.IO.File]::ReadAllText($compose), '(?m)^\s*image:\s*(postgres:\S+)\s*$')
        if ($m.Success) { return $m.Groups[1].Value }
    }
    throw 'Could not read the PostgreSQL image from compose.prod.yml: pass -PostgresImage.'
}

function Invoke-Docker([string[]] $Arguments) {
    # Windows PowerShell 5.1 turns anything a native program writes to stderr into an error when $ErrorActionPreference is 'Stop',
    # even if the program succeeded (pg_restore can print warnings). Only the exit code decides.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & $DockerExe @Arguments 2>&1 } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments[0]) failed: $(($output | Out-String).Trim())" }
    return ($output | Out-String)
}

function Invoke-VerifyRestore {
    $local = @(Get-LocalCopies)
    $target = $null
    if ($CopyName) {
        if ($CopyName -notmatch $NamePattern) { throw "-CopyName must look like controlweb-20260101T041500Z.dump.age" }
        $target = Join-Path $Destination $CopyName
    }
    elseif ($local.Count -gt 0) { $target = $local[0].FullName }
    $image = Get-ComposePostgresImage
    if ($DryRun) {
        Write-Dry "copy: $(if ($target) { $target } else { '(none downloaded yet)' })"
        Write-Dry "age --decrypt -i <identity> -o <temp>\restore.dump <copy>        (no pipes: the decrypted file is written by age itself)"
        Write-Dry "docker run -d --name controlweb-restore-check-<random> -m 512m $image"
        Write-Dry "docker cp <temp>\restore.dump ... ; pg_restore --no-owner --no-privileges --exit-on-error --single-transaction"
        Write-Dry "psql: count rows of sites, checks, incidents, alert_rules, users, flyway_schema_history"
        Write-Dry "docker rm -f the container; delete <temp>"
        return 0
    }
    if (-not $target -or -not (Test-Path -LiteralPath $target)) { throw 'There is no local copy to verify: run the download first.' }
    if (-not $Identity) { throw 'Pass -Identity <path of your age private key file> (the file that starts with AGE-SECRET-KEY-).' }
    if (-not (Test-Path -LiteralPath $Identity)) { throw "The identity file $Identity does not exist." }
    if (-not (Select-String -LiteralPath $Identity -Pattern 'AGE-SECRET-KEY-' -Quiet)) { throw "$Identity is not an age identity file." }
    $age = $null
    if ($env:CONTROLWEB_AGE_EXE) { if (Test-Path -LiteralPath $env:CONTROLWEB_AGE_EXE) { $age = Get-Item -LiteralPath $env:CONTROLWEB_AGE_EXE } }
    else { $age = Get-Command age -CommandType Application -ErrorAction SilentlyContinue }
    if (-not $age) { throw 'age.exe was not found. Install it without being an administrator: winget install FiloSottile.age' }
    try { [void](Invoke-Docker @('info', '--format', '{{.ServerVersion}}')) } catch { throw 'Docker is not running: start Docker Desktop.' }

    $work = Join-Path $env:TEMP ('controlweb-verify-' + [guid]::NewGuid().ToString('N'))
    $container = 'controlweb-restore-check-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $plain = Join-Path $work 'restore.dump'
        Write-Host "==> decrypting $(Split-Path $target -Leaf) (the identity is read by age; it is not printed or copied)"
        & $(if ($age.PSObject.Properties['Source']) { $age.Source } else { $age.FullName }) --decrypt -i $Identity -o $plain $target
        if ($LASTEXITCODE -ne 0) { throw 'age could not decrypt the copy: wrong identity file, or the copy is damaged.' }
        $head = New-Object byte[] 5
        $stream = [System.IO.File]::OpenRead($plain)
        try { [void] $stream.Read($head, 0, 5) } finally { $stream.Dispose() }
        if ([System.Text.Encoding]::ASCII.GetString($head) -ne 'PGDMP') { throw 'decrypted, but the content is not a PostgreSQL custom-format archive (no PGDMP header).' }
        Write-Host ("    decrypted: {0:N0} bytes, PostgreSQL archive header OK" -f (Get-Item -LiteralPath $plain).Length)

        Write-Host "==> starting a throw-away PostgreSQL ($image) with a 512 MiB limit"
        $password = -join ((1..24) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })   # a test value for a container that is deleted below
        [void](Invoke-Docker @('run', '-d', '--name', $container, '-m', '512m', '--memory-swap', '512m', '-e', "POSTGRES_PASSWORD=$password", '-e', 'POSTGRES_DB=restorecheck', $image))
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            $logs = Invoke-Docker @('logs', $container)
            if (([regex]::Matches($logs, 'ready to accept connections')).Count -ge 2) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw 'the temporary PostgreSQL did not become ready in 60 s.' }

        Write-Host '==> restoring (single transaction, stops at the first error)'
        [void](Invoke-Docker @('cp', $plain, "${container}:/tmp/restore.dump"))
        [void](Invoke-Docker @('exec', $container, 'pg_restore', '-U', 'postgres', '-d', 'restorecheck', '--no-owner', '--no-privileges', '--exit-on-error', '--single-transaction', '/tmp/restore.dump'))

        Write-Host '==> checking the data'
        $missing = @()
        foreach ($table in @('sites', 'checks', 'incidents', 'alert_rules', 'users', 'flyway_schema_history')) {
            try {
                $count = (Invoke-Docker @('exec', $container, 'psql', '-U', 'postgres', '-d', 'restorecheck', '-At', '-c', "select count(*) from $table")).Trim()
                Write-Host ("    {0,-24} {1} rows" -f $table, $count)
            }
            catch { $missing += $table }
        }
        if ($missing.Count -gt 0) { throw "tables missing or unreadable after the restore: $($missing -join ', ')" }
        $migrations = (Invoke-Docker @('exec', $container, 'psql', '-U', 'postgres', '-d', 'restorecheck', '-At', '-c', 'select count(*) || chr(32) || max(version) from flyway_schema_history where success')).Trim()
        Write-Host "    Flyway: $migrations (successful migrations, latest version)"
        Write-Host "RESTORE OK: $(Split-Path $target -Leaf) decrypts and restores." -ForegroundColor Green
        return 0
    }
    catch {
        Write-Host "RESTORE FAILED: $($_.Exception.Message)" -ForegroundColor Red
        return 1
    }
    finally {
        try { [void](Invoke-Docker @('rm', '-f', $container)) } catch { }
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host '    temporary container and decrypted file deleted'
    }
}

# ---- main --------------------------------------------------------------------------------------------------------------------
if ($RemoveTask) {
    if ($DryRun) { Write-Dry "unregister task '$TaskName'"; exit 0 }
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
    Write-Host "Task '$TaskName' removed." -ForegroundColor Green
    exit 0
}
if ($InstallTask) { Install-BackupTask; exit 0 }
if ($Status) { exit (Show-Status) }
if ($VerifyRestore) { exit (Invoke-VerifyRestore) }
if ($List) {
    if (-not $SshTarget) { throw 'No -SshTarget (and no CONTROLWEB_BACKUP_SSH): use cwbackup@host.' }
    if ($DryRun) { Write-Dry "ssh ${SshTarget}: list"; exit 0 }
    Get-RemoteCopies | ForEach-Object { '{0}  {1,12:N0} bytes' -f $_.Name, $_.Bytes }
    exit 0
}
exit (Invoke-Download)
