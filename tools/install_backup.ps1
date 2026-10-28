# Schedules tools\backup.py to run hidden every night at 04:30: worlds, plugin data and shared/ for every server,
# zipped to F:\Backups\BlackForgeMC\<date>, newest 7 kept. Log: tools\backup.log
# Remove it again with:  Unregister-ScheduledTask -TaskName 'BlackForgeMC Backup' -Confirm:$false
$pyw = Join-Path (Split-Path (Get-Command python).Source) 'pythonw.exe'
$script = Join-Path $PSScriptRoot 'backup.py'
$action = New-ScheduledTaskAction -Execute $pyw -Argument "`"$script`"" -WorkingDirectory (Split-Path $PSScriptRoot)
$trigger = New-ScheduledTaskTrigger -Daily -At 04:30
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2) -StartWhenAvailable -Hidden
Register-ScheduledTask -TaskName 'BlackForgeMC Backup' -Action $action -Trigger $trigger -Settings $settings `
    -Description "Nightly backup of every BlackForgeMC server to F:\Backups\BlackForgeMC (keeps 7)" -Force | Out-Null
Write-Host "BlackForgeMC Backup scheduled nightly at 04:30. Log: $PSScriptRoot\backup.log"
