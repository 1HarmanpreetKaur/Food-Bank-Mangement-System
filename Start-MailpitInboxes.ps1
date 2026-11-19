param(
    [Parameter(Mandatory = $true)]
    [string]$MailpitExe
)

$ErrorActionPreference = 'Stop'
$mailpit = (Resolve-Path -LiteralPath $MailpitExe).Path
$store = Join-Path $env:LOCALAPPDATA 'FoodBankMailpit'
New-Item -ItemType Directory -Path $store -Force | Out-Null

$inboxes = @(
    @{ Name = 'shared'; Web = 8025; Smtp = 1025 },
    @{ Name = 'coordinator1'; Web = 8026; Smtp = 1026 }
    @{ Name = 'coordinator2'; Web = 8027; Smtp = 1027 }
    @{ Name = 'volunteer1'; Web = 8028; Smtp = 1028 }
    @{ Name = 'volunteer2'; Web = 8029; Smtp = 1029 }
)

$started = @()
try {
    foreach ($inbox in $inboxes) {
        $recipient = '^' + $inbox.Name + '@test[.]com$'
        $arguments = @(
            '--listen', "127.0.0.1:$($inbox.Web)",
            '--smtp', "127.0.0.1:$($inbox.Smtp)",
            '--database', "$($inbox.Name).db"
        )
        if ($inbox.Name -ne 'shared') {
            $arguments += @('--smtp-allowed-recipients', $recipient)
        }
        $process = Start-Process -FilePath $mailpit -ArgumentList $arguments -WorkingDirectory $store -PassThru
        $started += $process
        if ($process.WaitForExit(750)) {
            throw "Mailpit for $($inbox.Name) stopped. Check whether either port is already in use."
        }
        Write-Host "$($inbox.Name) -> http://localhost:$($inbox.Web)"
    }
} catch {
    $started | Where-Object { -not $_.HasExited } | Stop-Process
    throw
}
