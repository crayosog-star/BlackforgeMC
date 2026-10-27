# Schedules tools\autoupdate.py to run hidden every 5 minutes (pulls GitHub, builds, restarts servers safely).
# Remove it again with:  Unregister-ScheduledTask -TaskName 'BlackForgeMC AutoUpdate' -Confirm:$false
$pyw = Join-Path (Split-Path (Get-Command python).Source) 'pythonw.exe'
$script = Join-Path $PSScriptRoot 'autoupdate.py'
$action = New-ScheduledTaskAction -Execute $pyw -Argument "`"$script`"" -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -StartWhenAvailable -Hidden
Register-ScheduledTask -TaskName 'BlackForgeMC AutoUpdate' -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Pulls BlackForgeMC from GitHub every 5 minutes and installs plugin updates' -Force | Out-Null
Write-Host "BlackForgeMC AutoUpdate scheduled: checks GitHub every 5 minutes. Log: $PSScriptRoot\autoupdate.log"
