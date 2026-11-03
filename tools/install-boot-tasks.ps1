# Makes the BlackForgeMC network run from the moment the PC boots - no Windows login needed.
#
# Run it ONCE from an administrator PowerShell:
#   Right-click Start > "Terminal (Admin)" / "Windows PowerShell (Admin)", then
#   powershell -ExecutionPolicy Bypass -File C:\MinecraftNetwork\tools\install-boot-tasks.ps1
#
# It asks for your Windows password in a Windows password box. The password goes to Windows Task Scheduler (which stores it the
# way Windows always does for "run whether the user is logged on or not" tasks). Nothing is written to any file.
#
# What it sets up, all running as your account:
#   BlackForgeMC Start       45 s after boot (and at login): starts the proxy and all five servers, skipping any already running
#   BlackForgeMC AutoUpdate  2 min after boot, then every 5 minutes: pulls what was pushed to GitHub, builds it, restarts the servers
#   BlackForgeMC AutoMerge   2 min after boot, then every 5 minutes: merges what was pushed
# If you change your Windows password later, run this script again.

#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$me = "$env:COMPUTERNAME\$env:USERNAME"
$cred = Get-Credential -UserName $me -Message "Your Windows password, so the servers can start before you log in"
$pw = $cred.GetNetworkCredential().Password

$py = (Get-Command pythonw).Source
$root = "C:\MinecraftNetwork"

function Boot-Trigger($delay) { $t = New-ScheduledTaskTrigger -AtStartup; $t.Delay = $delay; return $t }
$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)

# 1. start everything
$startAct = New-ScheduledTaskAction -Execute $py -Argument "`"$root\tools\autostart.py`"" -WorkingDirectory $root
$logon = New-ScheduledTaskTrigger -AtLogOn -User $me; $logon.Delay = "PT30S"
Register-ScheduledTask -TaskName "BlackForgeMC Start" -Action $startAct -Trigger @((Boot-Trigger "PT45S"), $logon) -Settings $set `
    -User $me -Password $pw -RunLevel Limited -Force -Description "Starts the BlackForgeMC proxy and servers at boot (skips any already running)." | Out-Null
"BlackForgeMC Start: set to run at boot"

# 2. the update pipeline: keep the actions it already has, add a boot trigger, run whether or not you are logged in
foreach ($name in "BlackForgeMC AutoUpdate", "BlackForgeMC AutoMerge") {
    $task = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
    if (-not $task) { "$name not found - skipped"; continue }
    $every = New-ScheduledTaskTrigger -Once -At (Get-Date).Date -RepetitionInterval (New-TimeSpan -Minutes 5)
    Register-ScheduledTask -TaskName $name -Action $task.Actions -Trigger @((Boot-Trigger "PT2M"), $every) -Settings $set `
        -User $me -Password $pw -RunLevel Limited -Force -Description $task.Description | Out-Null
    "${name}: set to run from boot, every 5 minutes"
}
$pw = $null
"Done. Restart the PC to test it: the servers should be up about two minutes after Windows starts."
