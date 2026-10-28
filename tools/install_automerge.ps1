# Schedules tools\automerge.py to run hidden every 5 minutes: merges Colin's (colinosbo) pull requests that build,
# pass tests and walkcheck, then installs them on the servers. Log: tools\autoupdate.log
# Remove it again with:  Unregister-ScheduledTask -TaskName 'BlackForgeMC AutoMerge' -Confirm:$false
$pyw = Join-Path (Split-Path (Get-Command python).Source) 'pythonw.exe'
$script = Join-Path $PSScriptRoot 'automerge.py'
$action = New-ScheduledTaskAction -Execute $pyw -Argument "`"$script`"" -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 45) -StartWhenAvailable -Hidden
Register-ScheduledTask -TaskName 'BlackForgeMC AutoMerge' -Action $action -Trigger $trigger -Settings $settings `
    -Description "Merges Colin's (colinosbo) BlackForgeMC pull requests every 5 minutes and installs them" -Force | Out-Null
Write-Host "BlackForgeMC AutoMerge scheduled: checks Colin's pull requests every 5 minutes. Log: $PSScriptRoot\autoupdate.log"
