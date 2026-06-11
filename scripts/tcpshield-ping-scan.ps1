$SubnetPrefix = "50.114.4"
$PingsPerHost = 5
$DelayBetweenIPs = 0.5
$OutputCsv = Join-Path $PSScriptRoot "PingResults_$SubnetPrefix.csv"

$results = @()

foreach ($i in 1..254) {
    $ip = "$SubnetPrefix.$i"
    Write-Host "Pinging $ip ..."

    $output = ping.exe -n $PingsPerHost $ip | Out-String

    if ($output -match "Average = (\d+)ms") {
        $avg = [int]$matches[1]
        Write-Host "$ip -> Avg: $avg ms" -ForegroundColor Green
    }
    else {
        $avg = $null
        Write-Host "$ip -> Unreachable" -ForegroundColor Red
    }

    $results += [PSCustomObject]@{
        IP = $ip
        AvgPingMs = $avg
    }

    Start-Sleep -Seconds $DelayBetweenIPs
}

$sorted = $results | Sort-Object @{
    Expression = {
        if ($null -eq $_.AvgPingMs) {
            [int]::MaxValue
        }
        else {
            $_.AvgPingMs
        }
    }
}

Write-Host "`n--- FINAL SORTED RESULTS ---`n" -ForegroundColor Cyan
$sorted | Format-Table -AutoSize

$sorted | Export-Csv -Path $OutputCsv -NoTypeInformation -Force
Write-Host "`nSaved results to: $OutputCsv" -ForegroundColor Cyan
