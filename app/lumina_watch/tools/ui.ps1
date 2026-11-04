param(
    [string]$Command = "help",
    [string]$Text = "",
    [string]$Out = "",
    [int]$X = 0,
    [int]$Y = 0,
    [string]$Direction = "down"
)

$ErrorActionPreference = "Continue"
$Root = "D:\AS project\LuminaWatch"
$Adb = "D:\ASAppData\platform-tools\adb.exe"
$Artifacts = Join-Path $Root ".artifacts"
New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

function Get-UiXml {
    for ($i = 0; $i -lt 4; $i++) {
        & $Adb shell rm -f /sdcard/ui.xml 2>&1 | Out-Null
        & $Adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
        & $Adb pull /sdcard/ui.xml (Join-Path $Artifacts "ui.xml") 2>&1 | Out-Null
        try {
            [xml]$doc = Get-Content (Join-Path $Artifacts "ui.xml") -Raw
            if ($doc.OuterXml.Length -gt 100) { return $doc }
        } catch { }
        Start-Sleep -Milliseconds 400
    }
    throw "uiautomator dump failed"
}

function Find-Node($doc, [string]$label) {
    $escaped = $label.Replace("'", "&apos;")
    $nodes = $doc.SelectNodes("//node[@text='$escaped'] | //node[@content-desc='$escaped']")
    if (-not $nodes -or $nodes.Count -eq 0) { return $null }
    return $nodes | Select-Object -First 1
}

function Convert-Bounds([string]$bounds) {
    $m = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { throw "bad bounds: $bounds" }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
}

switch ($Command) {
    "tap" {
        $doc = Get-UiXml
        $node = Find-Node $doc $Text
        if (-not $node) { Write-Error "TEXT_NOT_FOUND: $Text"; exit 2 }
        $pt = Convert-Bounds $node.bounds
        & $Adb shell input tap $pt[0] $pt[1]
        Write-Output "TAPPED $Text at $($pt[0]),$($pt[1])"
    }
    "tapxy" {
        & $Adb shell input tap $X $Y
        Write-Output "TAPPED $X,$Y"
    }
    "shot" {
        & $Adb shell screencap -p /sdcard/shot.png 2>&1 | Out-Null
        & $Adb pull /sdcard/shot.png (Join-Path $Artifacts $Out) 2>&1 | Out-Null
        Write-Output "SHOT $Out"
    }
    "scroll" {
        if ($Direction -eq "down") {
            & $Adb shell input swipe 540 1800 540 900 400
        } else {
            & $Adb shell input swipe 540 900 540 1800 400
        }
        Write-Output "SCROLLED $Direction"
    }
    "wait" {
        Start-Sleep -Seconds 1
        Write-Output "WAITED"
    }
    "exists" {
        $doc = Get-UiXml
        $node = Find-Node $doc $Text
        if ($node) { Write-Output "EXISTS" } else { Write-Output "MISSING" }
    }
    "dump" {
        $doc = Get-UiXml
        $doc.SelectNodes("//node[@text!='']") | ForEach-Object { Write-Output ("{0} | {1}" -f $_.text, $_.bounds) }
    }
    default {
        Write-Output "commands: tap -Text, tapxy -X -Y, shot -Out, scroll -Direction, wait, exists -Text, dump"
    }
}
