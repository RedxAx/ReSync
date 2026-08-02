$root = (Resolve-Path 'src/main/resources/nodes').Path
$files = [System.Linq.Enumerable]::OrderBy(
    [IO.FileInfo[]](Get-ChildItem $root -Recurse -File -Filter *.json | Where-Object { $_.Name -notlike '_*' }),
    [Func[IO.FileInfo, string]] { param($file) $file.FullName },
    [StringComparer]::OrdinalIgnoreCase
)
$sha = [Security.Cryptography.SHA256]::Create()
$stream = [IO.MemoryStream]::new()
$rows = foreach ($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
    $pathBytes = [Text.Encoding]::UTF8.GetBytes($relative + "`n")
    $stream.Write($pathBytes, 0, $pathBytes.Length)
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    $stream.Write($bytes, 0, $bytes.Length)
    $parsed = Get-Content -Raw $file.FullName | ConvertFrom-Json
    $definitions = if ($parsed -is [array]) { $parsed } else { @($parsed) }
    foreach ($definition in $definitions) {
        $inputs = if ($null -eq $definition.inputs) { @() } else { @($definition.inputs) }
        $outputs = if ($null -eq $definition.outputs) { @() } else { @($definition.outputs) }
        $handler = if ($null -eq $definition.handler) { '' } else { [string]$definition.handler }
        $operation = if ($null -eq $definition.handlerConfig -or $null -eq $definition.handlerConfig.operation) { '' } else { [string]$definition.handlerConfig.operation }
        $trigger = $null -ne $definition.trigger -and [bool]$definition.trigger
        $branchOutputs = @($outputs | Where-Object { $_.name -match '^(true|false|branch_|case|success|failure|cancel)' } | ForEach-Object { [string]$_.name })
        [ordered]@{
            sourcePath = $relative
            definitionId = [string]$definition.id
            handlerDeclaration = $handler
            bindingEvidence = if ($handler) { 'declared-only: module registration/runtime instance not proven by source descriptor' } elseif ($trigger) { 'trigger-route: FlowExecutor.resolveTriggerDefinition' } else { 'unresolved: no handler declaration and not a declared trigger' }
            handlerOperation = $operation
            threadPolicyEvidence = 'unverifiable: NodeHandler policy is runtime-instance behavior'
            effectEvidence = 'unverifiable: effect/authorization/failure semantics are not complete descriptor fields'
            inputPinCount = $inputs.Count
            outputPinCount = $outputs.Count
            defaults = @($inputs | Where-Object { $null -ne $_.defaultValue } | ForEach-Object { [string]$_.name })
            conversionsEvidence = if ($handler -eq 'ConversionHandler') { 'declared conversion handler operation: ' + $operation } else { 'unverifiable: no explicit conversion edge declared by this definition' }
            branchOutputs = $branchOutputs
            branchSemanticsEvidence = if ($branchOutputs.Count) { 'name-only: runtime branch semantics not proven by descriptor' } else { 'none declared' }
            triggerRoute = if ($trigger) { 'declared trigger; FlowExecutor.resolveTriggerDefinition fallback' } else { '' }
            status = if (!$handler -and !$trigger) { 'quarantine-required' } else { 'unverifiable' }
        }
    }
}
$hash = ($sha.ComputeHash($stream.ToArray()) | ForEach-Object ToString x2) -join ''
if ($hash -ne '140445208ee691fdf5e20fdc259765f335807e468bc2695fa438547e18e1cd73') {
    throw "C1 source hash differs from the Java verifier: $hash"
}
$payload = [ordered]@{
    format = 'c1-runtime-definition-matrix-v1'
    sourceHash = $hash
    definitionCount = @($rows).Count
    physicalInputCount = ($rows | ForEach-Object { $_.inputPinCount } | Measure-Object -Sum).Sum
    physicalOutputCount = ($rows | ForEach-Object { $_.outputPinCount } | Measure-Object -Sum).Sum
    defaultInputCount = ($rows | ForEach-Object { $_.defaults.Count } | Measure-Object -Sum).Sum
    logicalOmittedInputCount = 5
    rows = $rows
}
$target = 'src/test/resources/fixtures/node-replacement/runtime/c1/definition-runtime-matrix.json'
$payload | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $target
