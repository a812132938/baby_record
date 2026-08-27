param(
    [string]$ApiBase = $env:BABYAPP_API_BASE,
    [string]$DbHost = $env:BABYAPP_DB_HOST,
    [string]$DbPort = $env:BABYAPP_DB_PORT,
    [string]$DbName = $env:BABYAPP_DB_NAME,
    [string]$DbUser = $env:BABYAPP_DB_USER,
    [string]$DbPassword = $env:BABYAPP_DB_PASSWORD,
    [switch]$AllowNonLocalTarget,
    [string]$TestFailPoint = ''
)

$ErrorActionPreference = 'Stop'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Test-E2EFailPoint([string]$Name) {
    return $activeFailPoints -contains $Name
}

function Format-LocalDateTime($Value) {
    if ($Value -is [datetime]) { return $Value.ToString('yyyy-MM-ddTHH:mm:ss.fff') }
    return ([datetime]::Parse([string]$Value)).ToString('yyyy-MM-ddTHH:mm:ss.fff')
}

function Get-EventData($Event) {
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$Event.eventData)) "Event $($Event.id) did not return eventData"
    return $Event.eventData | ConvertFrom-Json
}

function Invoke-DatabaseScalar([string]$Sql) {
    $output = @(& $mysql -h $DbHost -P $DbPort -u $DbUser --batch --raw --skip-column-names $DbName -e $Sql 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "mysql exited with code $exitCode`: $(($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine)"
    }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Get-BabyEventCount([long]$BabyId) {
    return [int](Invoke-DatabaseScalar "SELECT COUNT(*) FROM baby_event WHERE baby_id = $BabyId;")
}

function Get-DatabaseCounts {
    $sql = "SELECT CONCAT_WS(',', (SELECT COUNT(*) FROM family), (SELECT COUNT(*) FROM baby), (SELECT COUNT(*) FROM app_user), (SELECT COUNT(*) FROM family_member), (SELECT COUNT(*) FROM trusted_device), (SELECT COUNT(*) FROM baby_event), (SELECT COUNT(*) FROM family_creation_recovery));"
    return Invoke-DatabaseScalar $sql
}

function Get-CreationEntityCounts {
    $sql = "SELECT CONCAT_WS(',', (SELECT COUNT(*) FROM family), (SELECT COUNT(*) FROM baby), (SELECT COUNT(*) FROM app_user), (SELECT COUNT(*) FROM family_member), (SELECT COUNT(*) FROM trusted_device), (SELECT COUNT(*) FROM family_creation_recovery));"
    return Invoke-DatabaseScalar $sql
}

function Get-CreationRecoveryCount([string]$CreatorDeviceUuid) {
    return Invoke-DatabaseScalar "SELECT COUNT(*) FROM family_creation_recovery WHERE device_id = '$CreatorDeviceUuid';"
}

function Get-CreationIdentity([string]$CreatorDeviceUuid) {
    $sql = "SELECT CONCAT_WS(CHAR(9), d.family_id, b.id, d.user_id, m.role, b.gender, b.birth_weight_grams) FROM trusted_device d JOIN baby b ON b.family_id = d.family_id JOIN family_member m ON m.family_id = d.family_id AND m.user_id = d.user_id WHERE d.device_id = '$CreatorDeviceUuid' LIMIT 1;"
    $row = Invoke-DatabaseScalar $sql
    $parts = $row -split "`t"
    Assert-True ($parts.Count -eq 6) 'Could not resolve the committed creation identity after the simulated lost response'
    return [PSCustomObject]@{
        FamilyId = [long]$parts[0]
        BabyId = [long]$parts[1]
        UserId = [long]$parts[2]
        Role = $parts[3]
        Gender = $parts[4]
        BirthWeightGrams = [int]$parts[5]
    }
}

function Remove-E2EFixture([string]$CreatorDeviceUuid) {
    if ($CreatorDeviceUuid -notmatch '^[0-9a-fA-F-]{36}$') {
        throw 'Fixture cleanup requires the random creator device UUID'
    }

    $fixtureRow = Invoke-DatabaseScalar "SELECT CONCAT_WS(CHAR(9), f.id, f.invite_code) FROM family f JOIN trusted_device d ON d.family_id = f.id WHERE d.device_id = '$CreatorDeviceUuid' LIMIT 1;"
    if ([string]::IsNullOrWhiteSpace($fixtureRow)) {
        Invoke-DatabaseScalar "DELETE FROM family_creation_recovery WHERE device_id = '$CreatorDeviceUuid';" | Out-Null
        $remainingDevice = Invoke-DatabaseScalar "SELECT COUNT(*) FROM trusted_device WHERE device_id = '$CreatorDeviceUuid';"
        Assert-True ($remainingDevice -eq '0') "Fixture family is missing but creator device remains: $CreatorDeviceUuid"
        $remainingRecovery = Invoke-DatabaseScalar "SELECT COUNT(*) FROM family_creation_recovery WHERE device_id = '$CreatorDeviceUuid';"
        Assert-True ($remainingRecovery -eq '0') "Fixture family is missing but creation recovery remains: $CreatorDeviceUuid"
        return
    }

    $fixtureParts = $fixtureRow -split "`t"
    Assert-True ($fixtureParts.Count -eq 2) 'Could not resolve the isolated E2E fixture'
    $fixtureFamilyId = [long]$fixtureParts[0]
    $fixtureInvite = $fixtureParts[1]
    Assert-True ($fixtureInvite -match '^[0-9A-F]{32}$') 'Refusing to clean a family without a dynamic 128-bit invite code'

    Invoke-DatabaseScalar "START TRANSACTION; CREATE TEMPORARY TABLE e2e_cleanup_users (id BIGINT UNSIGNED NOT NULL PRIMARY KEY); INSERT INTO e2e_cleanup_users (id) SELECT user_id FROM family_member WHERE family_id = $fixtureFamilyId; DELETE FROM family_creation_recovery WHERE family_id = $fixtureFamilyId OR device_id = '$CreatorDeviceUuid'; DELETE FROM baby_event WHERE baby_id IN (SELECT id FROM baby WHERE family_id = $fixtureFamilyId); DELETE FROM trusted_device WHERE family_id = $fixtureFamilyId; DELETE FROM family_member WHERE family_id = $fixtureFamilyId; DELETE FROM app_user WHERE id IN (SELECT id FROM e2e_cleanup_users) AND NOT EXISTS (SELECT 1 FROM trusted_device WHERE trusted_device.user_id = app_user.id) AND NOT EXISTS (SELECT 1 FROM family_member WHERE family_member.user_id = app_user.id) AND NOT EXISTS (SELECT 1 FROM baby_event WHERE baby_event.operator_id = app_user.id); DELETE FROM baby WHERE family_id = $fixtureFamilyId; DELETE FROM family WHERE id = $fixtureFamilyId AND invite_code = '$fixtureInvite'; DROP TEMPORARY TABLE e2e_cleanup_users; COMMIT;" | Out-Null
    $remaining = Invoke-DatabaseScalar "SELECT CONCAT_WS(',', (SELECT COUNT(*) FROM family WHERE id = $fixtureFamilyId), (SELECT COUNT(*) FROM trusted_device WHERE device_id = '$CreatorDeviceUuid'), (SELECT COUNT(*) FROM family_creation_recovery WHERE family_id = $fixtureFamilyId OR device_id = '$CreatorDeviceUuid'));"
    Assert-True ($remaining -eq '0,0,0') "Fixture cleanup left family/device/recovery rows behind: $remaining"
}

foreach ($required in @{
    BABYAPP_API_BASE = $ApiBase
    BABYAPP_DB_HOST = $DbHost
    BABYAPP_DB_PORT = $DbPort
    BABYAPP_DB_NAME = $DbName
    BABYAPP_DB_USER = $DbUser
    BABYAPP_DB_PASSWORD = $DbPassword
}.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace([string]$required.Value)) {
        throw "$($required.Key) is required"
    }
}

$ApiBase = $ApiBase.TrimEnd('/')
$apiUri = $null
if (-not [Uri]::TryCreate($ApiBase, [UriKind]::Absolute, [ref]$apiUri)) {
    throw 'BABYAPP_API_BASE must be an absolute HTTP(S) URL'
}
$dbHostNormalized = $DbHost.Trim().ToLowerInvariant()
$databaseIsLocal = $dbHostNormalized -in @('localhost', '127.0.0.1', '::1')
if ((-not $apiUri.IsLoopback -or -not $databaseIsLocal) -and -not $AllowNonLocalTarget) {
    throw 'Refusing to run destructive E2E cleanup against a non-local API or database. Use -AllowNonLocalTarget only for an isolated disposable environment.'
}
$mysql = (Get-Command mysql -ErrorAction Stop).Source
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $DbPassword
$creatorSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$lostResponseSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$unauthenticatedSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$memberSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$protectedSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$createdEvents = [System.Collections.Generic.List[object]]::new()
$sseProcess = $null
$creatorDeviceId = [guid]::NewGuid().ToString()
$creatorCreationKey = [guid]::NewGuid().ToString()
$memberDeviceId = [guid]::NewGuid().ToString()
$protectedDeviceId = [guid]::NewGuid().ToString()
$protectedCreationKey = [guid]::NewGuid().ToString()
$fixtureMayExist = $false
$protectedFixtureMayExist = $false
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$mainError = $null
$result = $null
$baselineCounts = ''
$countsAfterCleanup = ''
$activeFailPoints = @($TestFailPoint -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })

try {
    $allowedFailPoints = @('AfterFixture', 'AfterClaim', 'SyntheticCleanupFailure')
    $invalidFailPoints = @($activeFailPoints | Where-Object { $_ -notin $allowedFailPoints })
    Assert-True ($invalidFailPoints.Count -eq 0) "Unknown E2E failpoint(s): $($invalidFailPoints -join ', ')"

    $baselineCounts = Get-DatabaseCounts
    $fixtureSuffix = [guid]::NewGuid().ToString('N').Substring(0, 8).ToUpperInvariant()
    $birthDate = (Get-Date).Date.AddDays(-17).ToString('yyyy-MM-dd')
    $familyName = "E2E-FAMILY-$fixtureSuffix"
    $babyName = "E2E-BABY-$fixtureSuffix"
    $creatorNickname = "e2e-admin-$fixtureSuffix"
    $memberNickname = "e2e-member-$fixtureSuffix"

    # Mark the fixture before the request. If the HTTP response is interrupted after
    # commit, cleanup can still resolve the new family from this unique device UUID.
    $fixtureMayExist = $true
    $createBody = @{
        creationKey = $creatorCreationKey
        familyName = $familyName
        babyNickname = $babyName
        birthDate = $birthDate
        gender = 'GIRL'
        birthWeightGrams = 3250
        nickname = $creatorNickname
        deviceId = $creatorDeviceId
        deviceName = "E2E-ADMIN-$fixtureSuffix"
    } | ConvertTo-Json -Compress

    # Simulate a client losing the successful response after the server commits.
    # The disposable session deliberately discards both the response and its cookie.
    Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/auth/family/create" -WebSession $lostResponseSession -ContentType 'application/json' -Body $createBody | Out-Null
    $countsAfterLostResponse = Get-CreationEntityCounts
    $committedCreation = Get-CreationIdentity $creatorDeviceId

    # A retry with the exact same recovery contract must return the committed
    # identities and bind the durable cookie to this real creator session.
    $first = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/auth/family/create" -WebSession $creatorSession -ContentType 'application/json' -Body $createBody
    $countsAfterRecovery = Get-CreationEntityCounts
    Assert-True ($first.familyId -gt 0) 'Family creation did not return familyId'
    Assert-True ($first.babyId -gt 0) 'Family creation did not return babyId'
    Assert-True ($first.userId -gt 0) 'Family creation did not return userId'
    Assert-True ($first.role -eq 'ADMIN') 'Family creator is not ADMIN'
    Assert-True ($first.familyId -eq $committedCreation.FamilyId) 'Creation recovery returned a different familyId'
    Assert-True ($first.babyId -eq $committedCreation.BabyId) 'Creation recovery returned a different babyId'
    Assert-True ($first.userId -eq $committedCreation.UserId) 'Creation recovery returned a different userId'
    Assert-True ($first.role -eq $committedCreation.Role) 'Creation recovery returned a different creator role'
    Assert-True ($committedCreation.Gender -eq 'GIRL') 'Family creation did not persist the baby gender'
    Assert-True ($committedCreation.BirthWeightGrams -eq 3250) 'Family creation did not persist the birth weight in grams'
    Assert-True ($countsAfterRecovery -eq $countsAfterLostResponse) "Creation recovery inserted duplicate entities: before=$countsAfterLostResponse; after=$countsAfterRecovery"

    $recoveryAfterRecovery = Get-CreationRecoveryCount $creatorDeviceId
    Assert-True ($recoveryAfterRecovery -eq '1') "Creation recovery did not retain exactly one short-lived credential: $recoveryAfterRecovery"
    $confirmBody = @{ creationKey = $creatorCreationKey } | ConvertTo-Json -Compress
    $unauthenticatedConfirm = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/v1/auth/family/create/confirm" -WebSession $unauthenticatedSession -ContentType 'application/json' -Body $confirmBody -SkipHttpErrorCheck
    Assert-True ($unauthenticatedConfirm.StatusCode -eq 401) 'Unauthenticated creation confirmation was not rejected'
    $recoveryAfterUnauthenticatedConfirm = Get-CreationRecoveryCount $creatorDeviceId
    Assert-True ($recoveryAfterUnauthenticatedConfirm -eq '1') 'Unauthenticated confirmation removed the creation recovery credential'

    $confirmed = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/v1/auth/family/create/confirm" -WebSession $creatorSession -ContentType 'application/json' -Body $confirmBody -SkipHttpErrorCheck
    Assert-True ($confirmed.StatusCode -eq 204) "Authenticated creation confirmation returned HTTP $($confirmed.StatusCode) instead of 204"
    $recoveryAfterConfirm = Get-CreationRecoveryCount $creatorDeviceId
    Assert-True ($recoveryAfterConfirm -eq '0') 'Authenticated confirmation did not remove the creation recovery credential'

    $countsBeforeConfirmedReplay = Get-CreationEntityCounts
    $confirmedReplay = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/v1/auth/family/create" -WebSession $creatorSession -ContentType 'application/json' -Body $createBody -SkipHttpErrorCheck
    Assert-True ($confirmedReplay.StatusCode -eq 409) 'Confirmed creationKey replay was not rejected with HTTP 409'
    $countsAfterConfirmedReplay = Get-CreationEntityCounts
    Assert-True ($countsAfterConfirmedReplay -eq $countsBeforeConfirmedReplay) "Confirmed creationKey replay inserted entities: before=$countsBeforeConfirmedReplay; after=$countsAfterConfirmedReplay"

    $family = Invoke-RestMethod -Uri "$ApiBase/api/v1/family/invite" -WebSession $creatorSession
    Assert-True ($family.inviteCode -match '^[0-9A-F]{32}$') 'Created family invite is not dynamic 128-bit hex'
    $fixtureInvite = $family.inviteCode
    if (Test-E2EFailPoint 'AfterFixture') { throw 'E2E failpoint: AfterFixture' }

    $claimBody = @{
        inviteCode = $fixtureInvite
        nickname = $memberNickname
        deviceId = $memberDeviceId
        deviceName = "E2E-MEMBER-$fixtureSuffix"
    } | ConvertTo-Json -Compress
    $member = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/auth/device/claim" -WebSession $memberSession -ContentType 'application/json' -Body $claimBody
    Assert-True ($member.familyId -eq $first.familyId) 'Invite claim returned a different familyId'
    Assert-True ($member.babyId -eq $first.babyId) 'Invite claim returned a different babyId'
    Assert-True ($member.role -eq 'MEMBER') 'Invited family member is not MEMBER'
    Assert-True ($member.userId -ne $first.userId) 'Invited device reused the creator user'
    if (Test-E2EFailPoint 'AfterClaim') { throw 'E2E failpoint: AfterClaim' }

    $repeatedMember = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/auth/device/claim" -WebSession $memberSession -ContentType 'application/json' -Body $claimBody
    Assert-True ($repeatedMember.userId -eq $member.userId) 'Repeated claim created another member user'
    Assert-True ($repeatedMember.role -eq 'MEMBER') 'Repeated claim changed the member role'

    $me = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/me" -WebSession $creatorSession
    $memberMe = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/me" -WebSession $memberSession
    Assert-True ($me.familyId -eq $first.familyId -and $me.babyId -eq $first.babyId) 'Creator /auth/me identity mismatch'
    Assert-True ($memberMe.familyId -eq $first.familyId -and $memberMe.babyId -eq $first.babyId) 'Member /auth/me identity mismatch'

    $protectedSuffix = [guid]::NewGuid().ToString('N').Substring(0, 8).ToUpperInvariant()
    $protectedFixtureMayExist = $true
    $protected = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/auth/family/create" -WebSession $protectedSession -ContentType 'application/json' -Body (@{
        creationKey = $protectedCreationKey
        familyName = "E2E-PROTECTED-$protectedSuffix"
        babyNickname = "E2E-PROTECTED-BABY-$protectedSuffix"
        birthDate = (Get-Date).Date.AddDays(-5).ToString('yyyy-MM-dd')
        gender = 'BOY'
        birthWeightGrams = 3100
        nickname = "e2e-protected-$protectedSuffix"
        deviceId = $protectedDeviceId
        deviceName = "E2E-PROTECTED-$protectedSuffix"
    } | ConvertTo-Json -Compress)
    Assert-True ($protected.familyId -ne $first.familyId) 'Cross-family fixture reused the primary family'
    Assert-True ($protected.babyId -ne $first.babyId) 'Cross-family fixture reused the primary baby'

    $forbidden = Invoke-WebRequest -Uri "$ApiBase/api/v1/babies/$($protected.babyId)/dashboard" -WebSession $creatorSession -SkipHttpErrorCheck
    Assert-True ($forbidden.StatusCode -eq 403) 'Access to a baby from another family was not rejected'

    $baselineDashboard = Invoke-RestMethod -Uri "$ApiBase/api/v1/babies/$($me.babyId)/dashboard" -WebSession $creatorSession
    Assert-True ($baselineDashboard.baby.nickname -eq $babyName) 'Dashboard did not return the created baby nickname'
    Assert-True ([string]$baselineDashboard.baby.birthday -eq $birthDate) 'Dashboard did not return the created birth date'
    Assert-True ([string]$baselineDashboard.baby.gender -eq 'GIRL') 'Dashboard did not return the created baby gender'
    Assert-True ([int]$baselineDashboard.baby.birthWeightGrams -eq 3250) 'Dashboard did not return the created birth weight in grams'
    Assert-True (@($baselineDashboard.timeline).Count -eq 0) 'Isolated baby unexpectedly has timeline events'
    Assert-True (@($baselineDashboard.feedQuickAmounts).Count -eq 0) 'Isolated baby unexpectedly has quick amounts'
    Assert-True ($null -eq $baselineDashboard.activeSleep) 'Isolated baby unexpectedly has an active sleep'

    $devicesResponse = Invoke-RestMethod -Uri "$ApiBase/api/v1/family/devices" -WebSession $creatorSession
    $devices = if ($null -eq $devicesResponse) { @() } else { @($devicesResponse) }
    Assert-True ($devices.Count -eq 2) 'Family device list does not contain creator and invited member'
    Assert-True (@($devices.id) -contains $first.deviceId) 'Family device list is missing the creator device'
    Assert-True (@($devices.id) -contains $member.deviceId) 'Family device list is missing the invited member device'

    # Model two phones: the invited member listens while the creator writes.
    $cookie = $memberSession.Cookies.GetCookies([uri]$ApiBase) | Where-Object Name -eq 'br_device' | Select-Object -First 1
    Assert-True ($null -ne $cookie) 'Invited member device cookie was not issued'

    $sseStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $sseStartInfo.FileName = 'curl.exe'
    $sseStartInfo.UseShellExecute = $false
    $sseStartInfo.RedirectStandardOutput = $true
    $sseStartInfo.RedirectStandardError = $true
    $sseStartInfo.CreateNoWindow = $true
    @(
        '-sS', '-N', '--max-time', '20', '-b', "br_device=$($cookie.Value)",
        "$ApiBase/api/v1/babies/$($me.babyId)/stream"
    ) | ForEach-Object { [void]$sseStartInfo.ArgumentList.Add($_) }
    $sseProcess = [System.Diagnostics.Process]::new()
    $sseProcess.StartInfo = $sseStartInfo
    Assert-True $sseProcess.Start() 'Could not start the SSE client'
    $sseLines = [System.Collections.Generic.List[string]]::new()

    $sseConnected = $false
    while (($sseLine = $sseProcess.StandardOutput.ReadLine()) -ne $null) {
        $sseLines.Add($sseLine)
        if ($sseLine -match '^event:\s*connected') {
            $sseConnected = $true
            break
        }
    }
    Assert-True $sseConnected 'SSE did not connect before the change request'

    $amount = Get-Random -Minimum 60 -Maximum 181
    $feed = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feed" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        amountMl = $amount
        clientEventId = [guid]::NewGuid().ToString()
    } | ConvertTo-Json -Compress)
    $createdEvents.Add($feed)
    Assert-True ($feed.eventType -eq 'FEED') 'Legacy feed endpoint no longer creates FEED events'
    Assert-True ($feed.operatorName -eq $creatorNickname) 'Legacy feed did not return the server-resolved creator nickname'

    $sseChanged = $false
    while (($sseLine = $sseProcess.StandardOutput.ReadLine()) -ne $null) {
        $sseLines.Add($sseLine)
        if ($sseLine -match '^event:\s*changed') {
            $sseChanged = $true
            break
        }
    }
    if (-not $sseProcess.HasExited) { Stop-Process -Id $sseProcess.Id }
    $sseProcess.WaitForExit(2000) | Out-Null
    $sseProcess = $null
    $sseText = $sseLines -join "`n"
    Assert-True ($sseText -match 'event:\s*connected') 'SSE did not connect'
    Assert-True $sseChanged 'SSE did not receive a change event'

    $newAmount = if ($amount -lt 180) { $amount + 1 } else { $amount - 1 }
    $expectedUpdatedAt = Format-LocalDateTime $feed.updatedAt
    $storedUpdatedAt = Invoke-DatabaseScalar "SELECT DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s.%f') FROM baby_event WHERE id = $($feed.id);"
    Assert-True ($storedUpdatedAt.StartsWith($expectedUpdatedAt)) "API event version differs from MariaDB: api=$expectedUpdatedAt; db=$storedUpdatedAt"
    $updated = Invoke-RestMethod -Method Patch -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/$($feed.id)" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        amountMl = $newAmount
        expectedUpdatedAt = $expectedUpdatedAt
    } | ConvertTo-Json -Compress)
    $createdEvents[0] = $updated
    Assert-True ($updated.amountMl -eq $newAmount) 'Feed update did not persist'
    Assert-True ($updated.eventType -eq 'FEED') 'Legacy feed changed type during update'
    Assert-True ($updated.operatorName -eq $creatorNickname) 'Legacy feed update lost the server-resolved creator nickname'

    # Exercise each feeding type with a stable timestamp on the current local day.
    # The two sessions alternate writes so operatorName proves server-side attribution.
    $todayStart = (Get-Date).Date
    $feedingBaseTime = Get-Date
    if ($feedingBaseTime -gt $todayStart.AddMinutes(30)) {
        $feedingBaseTime = $feedingBaseTime.AddMinutes(-30)
    } else {
        $feedingBaseTime = $todayStart.AddSeconds(1)
    }

    $direct = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feeding" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        type = 'DIRECT_BREASTFEED'
        eventTime = $feedingBaseTime.ToString('yyyy-MM-ddTHH:mm:ss.fff')
        leftSeconds = 300
        rightSeconds = 180
        lastSide = 'RIGHT'
        segments = @(
            @{ side = 'LEFT'; seconds = 300 }
            @{ side = 'RIGHT'; seconds = 180 }
        )
        clientEventId = [guid]::NewGuid().ToString()
    } | ConvertTo-Json -Depth 4 -Compress)
    $createdEvents.Add($direct)
    $directData = Get-EventData $direct
    Assert-True ($direct.eventType -eq 'DIRECT_BREASTFEED') 'Direct breastfeeding returned the wrong event type'
    Assert-True ($null -eq $direct.amountMl) 'Direct breastfeeding incorrectly returned an intake amount'
    Assert-True ([int]$directData.leftSeconds -eq 300 -and [int]$directData.rightSeconds -eq 180) 'Direct breastfeeding did not preserve left/right seconds'
    Assert-True ($directData.lastSide -eq 'RIGHT') 'Direct breastfeeding did not preserve the ending side'
    $directSegments = @($directData.segments)
    Assert-True ($directSegments.Count -eq 2) 'Direct breastfeeding did not round-trip both timer segments'
    Assert-True ($directSegments[0].side -eq 'LEFT' -and [int]$directSegments[0].seconds -eq 300) 'Direct breastfeeding changed the first LEFT segment'
    Assert-True ($directSegments[1].side -eq 'RIGHT' -and [int]$directSegments[1].seconds -eq 180) 'Direct breastfeeding changed the second RIGHT segment'
    Assert-True ([int](($directSegments | Measure-Object -Property seconds -Sum).Sum) -eq 480) 'Direct breastfeeding segment total differs from left/right summaries'
    Assert-True ($direct.operatorName -eq $creatorNickname) 'Direct breastfeeding did not return the creator nickname'

    $bottleClientEventId = [guid]::NewGuid().ToString()
    $bottleCreateBody = @{
        type = 'BOTTLE_BREAST_MILK'
        eventTime = $feedingBaseTime.AddSeconds(1).ToString('yyyy-MM-ddTHH:mm:ss.fff')
        amountMl = 85
        clientEventId = $bottleClientEventId
    } | ConvertTo-Json -Compress
    $bottle = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feeding" -WebSession $memberSession -ContentType 'application/json' -Body $bottleCreateBody
    $createdEvents.Add($bottle)
    Assert-True ($bottle.eventType -eq 'BOTTLE_BREAST_MILK' -and [int]$bottle.amountMl -eq 85) 'Breast-milk bottle feed did not preserve its type and intake amount'
    Assert-True ($bottle.operatorName -eq $memberNickname) 'Breast-milk bottle feed did not return the invited member nickname'

    $eventCountAfterBottle = Get-BabyEventCount $me.babyId
    $sameBottleReplay = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feeding" -WebSession $creatorSession -ContentType 'application/json' -Body $bottleCreateBody
    Assert-True ([long]$sameBottleReplay.id -eq [long]$bottle.id) 'Same-payload feeding replay did not return the first event id'
    Assert-True ([int]$sameBottleReplay.amountMl -eq 85) 'Same-payload feeding replay changed the first write'
    Assert-True ($sameBottleReplay.operatorName -eq $memberNickname) 'Same-payload replay reassigned the record owner to the replaying device'
    Assert-True ((Get-BabyEventCount $me.babyId) -eq $eventCountAfterBottle) 'Same-payload feeding replay inserted another database event'

    $differentBottleReplay = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feeding" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        type = 'BOTTLE_BREAST_MILK'
        eventTime = $feedingBaseTime.AddSeconds(101).ToString('yyyy-MM-ddTHH:mm:ss.fff')
        amountMl = 155
        clientEventId = $bottleClientEventId
    } | ConvertTo-Json -Compress)
    Assert-True ([long]$differentBottleReplay.id -eq [long]$bottle.id) 'Different-payload feeding replay did not return the first event id'
    Assert-True ([int]$differentBottleReplay.amountMl -eq 85) 'Different-payload feeding replay overwrote the first intake amount'
    Assert-True ((Format-LocalDateTime $differentBottleReplay.startTime) -eq (Format-LocalDateTime ($feedingBaseTime.AddSeconds(1)))) 'Different-payload feeding replay overwrote the first event time'
    Assert-True ($differentBottleReplay.operatorName -eq $memberNickname) 'Different-payload replay reassigned the record owner to the replaying device'
    Assert-True ((Get-BabyEventCount $me.babyId) -eq $eventCountAfterBottle) 'Different-payload feeding replay inserted another database event'

    $formula = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feeding" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        type = 'FORMULA_FEED'
        eventTime = $feedingBaseTime.AddSeconds(2).ToString('yyyy-MM-ddTHH:mm:ss.fff')
        amountMl = 110
        clientEventId = [guid]::NewGuid().ToString()
    } | ConvertTo-Json -Compress)
    $createdEvents.Add($formula)
    Assert-True ($formula.eventType -eq 'FORMULA_FEED' -and [int]$formula.amountMl -eq 110) 'Formula feed did not preserve its type and intake amount'
    Assert-True ($formula.operatorName -eq $creatorNickname) 'Formula feed did not return the creator nickname'

    $pumping = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/feeding" -WebSession $memberSession -ContentType 'application/json' -Body (@{
        type = 'PUMPING'
        eventTime = $feedingBaseTime.AddSeconds(3).ToString('yyyy-MM-ddTHH:mm:ss.fff')
        amountMl = 80
        leftMl = 45
        rightMl = 35
        durationSeconds = 600
        clientEventId = [guid]::NewGuid().ToString()
    } | ConvertTo-Json -Compress)
    $createdEvents.Add($pumping)
    $pumpingData = Get-EventData $pumping
    Assert-True ($pumping.eventType -eq 'PUMPING' -and [int]$pumping.amountMl -eq 80) 'Pumping did not return its side-total output'
    Assert-True ([int]$pumpingData.leftMl -eq 45 -and [int]$pumpingData.rightMl -eq 35 -and [int]$pumpingData.durationSeconds -eq 600) 'Pumping did not preserve side output and duration'
    Assert-True ($pumping.operatorName -eq $memberNickname) 'Pumping did not return the invited member nickname'

    $direct = Invoke-RestMethod -Method Patch -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/$($direct.id)" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        expectedUpdatedAt = Format-LocalDateTime $direct.updatedAt
        data = @{
            schemaVersion = 1
            leftSeconds = 420
            rightSeconds = 240
            lastSide = 'LEFT'
            segments = @(
                @{ side = 'LEFT'; seconds = 300 }
                @{ side = 'RIGHT'; seconds = 240 }
                @{ side = 'LEFT'; seconds = 120 }
            )
        }
    } | ConvertTo-Json -Depth 5 -Compress)
    $createdEvents[1] = $direct
    $directData = Get-EventData $direct
    Assert-True ($null -eq $direct.amountMl) 'Edited direct breastfeeding acquired an intake amount'
    Assert-True ([int]$directData.leftSeconds -eq 420 -and [int]$directData.rightSeconds -eq 240 -and $directData.lastSide -eq 'LEFT') 'Direct breastfeeding edit did not persist side durations'
    $directSegments = @($directData.segments)
    Assert-True ($directSegments.Count -eq 3) 'Direct breastfeeding edit did not round-trip all timer segments'
    Assert-True ($directSegments[0].side -eq 'LEFT' -and [int]$directSegments[0].seconds -eq 300) 'Direct breastfeeding edit changed the first segment'
    Assert-True ($directSegments[1].side -eq 'RIGHT' -and [int]$directSegments[1].seconds -eq 240) 'Direct breastfeeding edit changed the second segment'
    Assert-True ($directSegments[2].side -eq 'LEFT' -and [int]$directSegments[2].seconds -eq 120) 'Direct breastfeeding edit changed the third segment'
    Assert-True ([int](($directSegments | Where-Object side -eq 'LEFT' | Measure-Object -Property seconds -Sum).Sum) -eq 420) 'Edited LEFT segments differ from the leftSeconds summary'
    Assert-True ([int](($directSegments | Where-Object side -eq 'RIGHT' | Measure-Object -Property seconds -Sum).Sum) -eq 240) 'Edited RIGHT segments differ from the rightSeconds summary'
    Assert-True ((Format-LocalDateTime $direct.endTime) -eq (Format-LocalDateTime ($feedingBaseTime.AddSeconds(660)))) 'Direct breastfeeding edit did not recompute endTime from the edited duration'
    Assert-True ($direct.operatorName -eq $creatorNickname) 'Direct breastfeeding edit changed the record owner'

    $bottle = Invoke-RestMethod -Method Patch -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/$($bottle.id)" -WebSession $memberSession -ContentType 'application/json' -Body (@{
        expectedUpdatedAt = Format-LocalDateTime $bottle.updatedAt
        eventTime = $feedingBaseTime.AddSeconds(11).ToString('yyyy-MM-ddTHH:mm:ss.fff')
        amountMl = 95
    } | ConvertTo-Json -Compress)
    $createdEvents[2] = $bottle
    Assert-True ([int]$bottle.amountMl -eq 95) 'Breast-milk bottle feed edit did not persist the intake amount'
    Assert-True ((Format-LocalDateTime $bottle.startTime) -eq (Format-LocalDateTime $feedingBaseTime.AddSeconds(11))) 'Breast-milk bottle feed edit did not persist eventTime'
    Assert-True ($bottle.operatorName -eq $memberNickname) 'Breast-milk bottle feed edit changed the record owner'

    $formula = Invoke-RestMethod -Method Patch -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/$($formula.id)" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        expectedUpdatedAt = Format-LocalDateTime $formula.updatedAt
        amountMl = 120
    } | ConvertTo-Json -Compress)
    $createdEvents[3] = $formula
    Assert-True ([int]$formula.amountMl -eq 120) 'Formula feed edit did not persist the intake amount'
    Assert-True ($formula.operatorName -eq $creatorNickname) 'Formula feed edit changed the record owner'

    $pumping = Invoke-RestMethod -Method Patch -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/$($pumping.id)" -WebSession $memberSession -ContentType 'application/json' -Body (@{
        expectedUpdatedAt = Format-LocalDateTime $pumping.updatedAt
        amountMl = 90
        data = @{ schemaVersion = 1; leftMl = 50; rightMl = 40; durationSeconds = 720 }
    } | ConvertTo-Json -Depth 4 -Compress)
    $createdEvents[4] = $pumping
    $pumpingData = Get-EventData $pumping
    Assert-True ([int]$pumping.amountMl -eq 90) 'Pumping edit did not persist the side-total output'
    Assert-True ([int]$pumpingData.leftMl -eq 50 -and [int]$pumpingData.rightMl -eq 40 -and [int]$pumpingData.durationSeconds -eq 720) 'Pumping edit did not persist side output and duration'
    Assert-True ((Format-LocalDateTime $pumping.endTime) -eq (Format-LocalDateTime ($feedingBaseTime.AddSeconds(723)))) 'Pumping edit did not recompute endTime from the edited duration'
    Assert-True ($pumping.operatorName -eq $memberNickname) 'Pumping edit changed the record owner'

    $poop = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/simple" -WebSession $memberSession -ContentType 'application/json' -Body (@{
        type = 'POOP'
        clientEventId = [guid]::NewGuid().ToString()
        data = @{ color = 'runtime'; texture = 'e2e'; amount = 'small' }
    } | ConvertTo-Json -Depth 4 -Compress)
    $createdEvents.Add($poop)

    $sleepClientId = [guid]::NewGuid().ToString()
    $sleepEndTime = Get-Date
    $sleepStartTime = $sleepEndTime.AddMinutes(-2)
    if ($sleepStartTime.Date -ne $sleepEndTime.Date) { $sleepStartTime = $sleepEndTime.Date }
    $sleep = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/sleep/start" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        eventTime = $sleepStartTime.ToString('yyyy-MM-ddTHH:mm:ss.fff')
        clientEventId = $sleepClientId
    } | ConvertTo-Json -Compress)
    Assert-True ($sleep.clientEventId -eq $sleepClientId) 'Sleep start returned an event owned by another request'
    Assert-True ($sleep.operatorName -eq $creatorNickname) 'Sleep start did not return the creator nickname'
    Assert-True ($null -eq $sleep.endOperatorName) 'Active sleep unexpectedly has an ending operator'
    $createdEvents.Add($sleep)
    $sleepEnded = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/v1/babies/$($me.babyId)/sleep/end" -WebSession $memberSession -ContentType 'application/json' -Body (@{
        eventTime = $sleepEndTime.ToString('yyyy-MM-ddTHH:mm:ss.fff')
        clientEventId = $sleepClientId
    } | ConvertTo-Json -Compress)
    $createdEvents[$createdEvents.Count - 1] = $sleepEnded
    Assert-True ($null -ne $sleepEnded.endTime) 'Sleep did not end'
    Assert-True ($sleepEnded.operatorName -eq $creatorNickname) 'Ending sleep changed the starting record owner'
    Assert-True ($sleepEnded.endOperatorName -eq $memberNickname) 'Sleep end did not return the invited member nickname'

    $dashboard = Invoke-RestMethod -Uri "$ApiBase/api/v1/babies/$($me.babyId)/dashboard" -WebSession $memberSession
    Assert-True ([string]$dashboard.baby.birthday -eq $birthDate) 'Invited member does not see the created birth date'
    Assert-True (@($dashboard.feedQuickAmounts) -contains $newAmount) 'Quick amounts were not derived from real history'
    Assert-True (@($dashboard.feedQuickAmounts) -contains 95) 'Breast-milk bottle intake was not available as a quick amount'
    Assert-True (@($dashboard.feedQuickAmounts) -contains 120) 'Formula intake was not available as a quick amount'
    Assert-True ([int]$dashboard.today.feedCount -eq 4) 'Dashboard feedCount must include legacy feed and the three baby-feeding types, but exclude pumping'
    Assert-True ([int]$dashboard.today.totalMilkMl -eq ($newAmount + 95 + 120)) 'Dashboard totalMilkMl mixed pumping output into baby intake or lost a bottle intake'
    Assert-True ([int]$dashboard.today.directBreastfeedCount -eq 1 -and [int]$dashboard.today.directBreastfeedMinutes -eq 11) 'Dashboard direct breastfeeding summary is inaccurate'
    Assert-True ([int]$dashboard.today.bottleBreastMilkCount -eq 1 -and [int]$dashboard.today.bottleBreastMilkMl -eq 95) 'Dashboard breast-milk bottle summary is inaccurate'
    Assert-True ([int]$dashboard.today.formulaFeedCount -eq 1 -and [int]$dashboard.today.formulaFeedMl -eq 120) 'Dashboard formula summary is inaccurate'
    Assert-True ([int]$dashboard.today.pumpingCount -eq 1 -and [int]$dashboard.today.pumpingMl -eq 90 -and [int]$dashboard.today.pumpingMinutes -eq 12) 'Dashboard pumping summary is inaccurate'

    $dashboardTimeline = @($dashboard.timeline)
    $dashboardDirect = @($dashboardTimeline | Where-Object { [long]$_.id -eq [long]$direct.id })
    $dashboardPumping = @($dashboardTimeline | Where-Object { [long]$_.id -eq [long]$pumping.id })
    $dashboardSleep = @($dashboardTimeline | Where-Object { [long]$_.id -eq [long]$sleepEnded.id })
    Assert-True ($dashboardDirect.Count -eq 1 -and $dashboardDirect[0].operatorName -eq $creatorNickname) 'Dashboard timeline lost the creator attribution'
    Assert-True ($dashboardPumping.Count -eq 1 -and $dashboardPumping[0].operatorName -eq $memberNickname) 'Dashboard timeline lost the invited member attribution'
    Assert-True ($dashboardSleep.Count -eq 1 -and $dashboardSleep[0].operatorName -eq $creatorNickname -and $dashboardSleep[0].endOperatorName -eq $memberNickname) 'Dashboard timeline lost the sleep start/end attribution'

    $stats = Invoke-RestMethod -Uri "$ApiBase/api/v1/babies/$($me.babyId)/stats?days=7" -WebSession $memberSession
    Assert-True (@($stats.days).Count -eq 7) 'Stats did not return seven days'

    $date = Get-Date -Format 'yyyy-MM-dd'
    $todayStats = @($stats.days | Where-Object { $_.date -eq $date })
    Assert-True ($todayStats.Count -eq 1) 'Stats did not return exactly one current-day row'
    $todayStat = $todayStats[0]
    Assert-True ([int]$todayStat.feedCount -eq 4) 'Stats feedCount must include legacy feed and the three baby-feeding types, but exclude pumping'
    Assert-True ([int]$todayStat.milkMl -eq ($newAmount + 95 + 120)) 'Stats milkMl mixed pumping output into baby intake or lost a bottle intake'
    Assert-True ([int]$todayStat.directBreastfeedCount -eq 1 -and [int]$todayStat.directBreastfeedMinutes -eq 11) 'Stats direct breastfeeding fields are inaccurate'
    Assert-True ([int]$todayStat.bottleBreastMilkCount -eq 1 -and [int]$todayStat.bottleBreastMilkMl -eq 95) 'Stats breast-milk bottle fields are inaccurate'
    Assert-True ([int]$todayStat.formulaFeedCount -eq 1 -and [int]$todayStat.formulaFeedMl -eq 120) 'Stats formula fields are inaccurate'
    Assert-True ([int]$todayStat.pumpingCount -eq 1 -and [int]$todayStat.pumpingMl -eq 90 -and [int]$todayStat.pumpingMinutes -eq 12) 'Stats pumping fields are inaccurate'

    $historyResponse = Invoke-RestMethod -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events?date=$date" -WebSession $creatorSession
    $history = if ($null -eq $historyResponse) { @() } else { @($historyResponse) }
    Assert-True ($history.Count -eq $createdEvents.Count) 'History did not return exactly the isolated E2E events'
    $historyIds = @($history | ForEach-Object { [long]$_.id })
    foreach ($event in @($createdEvents)) {
        Assert-True ($historyIds -contains [long]$event.id) "History is missing created event $($event.id)"
    }
    $legacyHistory = @($history | Where-Object { [long]$_.id -eq [long]$updated.id })
    $directHistory = @($history | Where-Object { [long]$_.id -eq [long]$direct.id })
    $bottleHistory = @($history | Where-Object { [long]$_.id -eq [long]$bottle.id })
    $formulaHistory = @($history | Where-Object { [long]$_.id -eq [long]$formula.id })
    $pumpingHistory = @($history | Where-Object { [long]$_.id -eq [long]$pumping.id })
    $sleepHistory = @($history | Where-Object { [long]$_.id -eq [long]$sleepEnded.id })
    Assert-True ($legacyHistory.Count -eq 1 -and $legacyHistory[0].eventType -eq 'FEED' -and $legacyHistory[0].operatorName -eq $creatorNickname) 'History no longer reads legacy FEED with its record owner'
    Assert-True ($directHistory.Count -eq 1 -and $directHistory[0].eventType -eq 'DIRECT_BREASTFEED' -and $directHistory[0].operatorName -eq $creatorNickname) 'History lost the direct breastfeeding type or creator attribution'
    Assert-True ($bottleHistory.Count -eq 1 -and $bottleHistory[0].eventType -eq 'BOTTLE_BREAST_MILK' -and $bottleHistory[0].operatorName -eq $memberNickname) 'History lost the breast-milk bottle type or member attribution'
    Assert-True ($formulaHistory.Count -eq 1 -and $formulaHistory[0].eventType -eq 'FORMULA_FEED' -and $formulaHistory[0].operatorName -eq $creatorNickname) 'History lost the formula type or creator attribution'
    Assert-True ($pumpingHistory.Count -eq 1 -and $pumpingHistory[0].eventType -eq 'PUMPING' -and $pumpingHistory[0].operatorName -eq $memberNickname) 'History lost the pumping type or member attribution'
    Assert-True ($sleepHistory.Count -eq 1 -and $sleepHistory[0].operatorName -eq $creatorNickname -and $sleepHistory[0].endOperatorName -eq $memberNickname) 'History lost the sleep start/end attribution'

    $profileName = "e2e-baby-updated-$fixtureSuffix"
    $profile = Invoke-RestMethod -Method Patch -Uri "$ApiBase/api/v1/babies/$($me.babyId)" -WebSession $creatorSession -ContentType 'application/json' -Body (@{
        nickname = $profileName
        birthday = $birthDate
        gender = 'GIRL'
        birthWeightGrams = 3250
    } | ConvertTo-Json -Compress)
    Assert-True ($profile.nickname -eq $profileName) 'Baby profile did not update'
    Assert-True ([string]$profile.birthday -eq $birthDate) 'Baby profile update lost the birth date'
    Assert-True ([string]$profile.gender -eq 'GIRL') 'Baby profile update lost the gender'
    Assert-True ([int]$profile.birthWeightGrams -eq 3250) 'Baby profile update lost the birth weight'

    foreach ($event in @($createdEvents)) {
        $version = [uri]::EscapeDataString((Format-LocalDateTime $event.updatedAt))
        Invoke-RestMethod -Method Delete -Uri "$ApiBase/api/v1/babies/$($me.babyId)/events/$($event.id)?expectedUpdatedAt=$version" -WebSession $creatorSession | Out-Null
    }
    $createdEvents.Clear()

    $clean = Invoke-RestMethod -Uri "$ApiBase/api/v1/babies/$($me.babyId)/dashboard" -WebSession $creatorSession
    Assert-True (@($clean.timeline).Count -eq 0) 'E2E events were not cleaned through the API'
    Assert-True (@($clean.feedQuickAmounts).Count -eq 0) 'Deleted feeds still affect quick amounts'
    Assert-True ([int]$clean.today.feedCount -eq 0 -and [int]$clean.today.totalMilkMl -eq 0) 'Deleted feeding events still affect dashboard intake totals'
    Assert-True ([int]$clean.today.directBreastfeedCount -eq 0 -and [int]$clean.today.bottleBreastMilkCount -eq 0 -and [int]$clean.today.formulaFeedCount -eq 0 -and [int]$clean.today.pumpingCount -eq 0) 'Deleted feeding events still affect dashboard type counts'

    $result = [PSCustomObject]@{
        FamilyId = $me.familyId
        BabyId = $me.babyId
        CreatorRole = $me.role
        MemberRole = $memberMe.role
        BirthDate = $birthDate
        Gender = 'GIRL'
        BirthWeightGrams = 3250
        InviteCodeLength = $fixtureInvite.Length
        RepeatedClaimSameUser = $true
        FamilyDeviceCount = $devices.Count
        CrossBabyId = $protected.babyId
        CrossBabyStatus = $forbidden.StatusCode
        SseConnected = $true
        SseChanged = $true
        DynamicAmount = $newAmount
        FeedingTypesTested = @('DIRECT_BREASTFEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED', 'PUMPING')
        OperatorNamesTested = @($creatorNickname, $memberNickname)
        FeedingIdempotencyFirstWriteWins = $true
        SleepStartOperatorName = $sleepEnded.operatorName
        SleepEndOperatorName = $sleepEnded.endOperatorName
        DashboardMilkMl = [int]$dashboard.today.totalMilkMl
        DashboardPumpingMl = [int]$dashboard.today.pumpingMl
        StatsMilkMl = [int]$todayStat.milkMl
        StatsPumpingMl = [int]$todayStat.pumpingMl
        LegacyFeedCompatible = $true
        SleepLifecycleTested = $true
        StatsDays = @($stats.days).Count
        HistoryEventsBeforeCleanup = @($history).Count
        EventsAfterCleanup = @($clean.timeline).Count
        DatabaseCountsBefore = $baselineCounts
        CreationRecoverySameIds = $true
        CreationCountsAfterLostResponse = $countsAfterLostResponse
        CreationCountsAfterRecovery = $countsAfterRecovery
        RecoveryRowsBeforeConfirm = [int]$recoveryAfterRecovery
        UnauthenticatedConfirmStatus = $unauthenticatedConfirm.StatusCode
        ConfirmStatus = $confirmed.StatusCode
        RecoveryRowsAfterConfirm = [int]$recoveryAfterConfirm
        ConfirmedReplayStatus = $confirmedReplay.StatusCode
    }
}
catch {
    $mainError = $_
}
finally {
    if ($null -ne $sseProcess) {
        try {
            if (-not $sseProcess.HasExited) { Stop-Process -Id $sseProcess.Id }
            $sseProcess.WaitForExit(2000) | Out-Null
        } catch { $cleanupErrors.Add("SSE process: $($_.Exception.Message)") }
    }
    if ($protectedFixtureMayExist) {
        try {
            Remove-E2EFixture $protectedDeviceId
            $protectedFixtureMayExist = $false
        } catch { $cleanupErrors.Add("protected database fixture: $($_.Exception.Message)") }
    }
    if ($fixtureMayExist) {
        try {
            Remove-E2EFixture $creatorDeviceId
            $fixtureMayExist = $false
        } catch { $cleanupErrors.Add("database fixture: $($_.Exception.Message)") }
    }
    if (-not [string]::IsNullOrWhiteSpace($baselineCounts)) {
        try {
            $countsAfterCleanup = Get-DatabaseCounts
            Assert-True ($countsAfterCleanup -eq $baselineCounts) "E2E cleanup changed database counts: $countsAfterCleanup; expected $baselineCounts"
        } catch { $cleanupErrors.Add("database counts: $($_.Exception.Message)") }
    }
    try { $env:MYSQL_PWD = $previousMysqlPassword } catch { $cleanupErrors.Add("MYSQL_PWD restore: $($_.Exception.Message)") }
    if (Test-E2EFailPoint 'SyntheticCleanupFailure') { $cleanupErrors.Add('synthetic cleanup failure') }
}

if ($cleanupErrors.Count -gt 0) {
    $cleanupMessage = $cleanupErrors -join '; '
    if ($null -ne $mainError) {
        throw [System.Exception]::new("E2E failed: $($mainError.Exception.Message); cleanup also failed: $cleanupMessage", $mainError.Exception)
    }
    throw "E2E cleanup failed: $cleanupMessage"
}
if ($null -ne $mainError) { throw $mainError }

$result | Add-Member -NotePropertyName DatabaseCountsAfter -NotePropertyValue $countsAfterCleanup
$result | Add-Member -NotePropertyName TestFixtureCleaned -NotePropertyValue $true
$result | ConvertTo-Json -Compress
