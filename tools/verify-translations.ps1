param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
function Read-Strings([string]$Directory) {
    $result = @{}
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter '*.xml') {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
        foreach ($item in $xml.SelectNodes('/resources/string')) {
            if ($item.GetAttribute('translatable') -eq 'false') { continue }
            $key = $item.GetAttribute('name')
            if ($result.ContainsKey($key)) { throw "Duplicate string $key in $Directory" }
            $result[$key] = $item.InnerText.Trim('"')
        }
    }
    return $result
}
$resources = Join-Path $ProjectRoot 'app/src/main/res'
$english = Read-Strings (Join-Path $resources 'values')
foreach ($locale in @('fi','de','fr','es','pt','it','sv','pl','nl','tr','cs')) {
    $localized = Read-Strings (Join-Path $resources "values-$locale")
    foreach ($key in $english.Keys) {
        if (-not $localized.ContainsKey($key)) { throw "$locale missing $key" }
        if ([string]::IsNullOrWhiteSpace($localized[$key])) { throw "$locale empty $key" }
        $expected = @([regex]::Matches($english[$key], '%(?:[0-9]+\$)?[ds]') | ForEach-Object Value) -join '|'
        $actual = @([regex]::Matches($localized[$key], '%(?:[0-9]+\$)?[ds]') | ForEach-Object Value) -join '|'
        if ($expected -cne $actual) { throw "$locale placeholders differ: $key" }
    }
    Write-Output "$locale : $($english.Count) keys covered, placeholders match"
}
# Every literal bilingual call must be represented by the resource bridge.
$catalogue = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot 'app/src/main/java/fi/radioplus/app/TranslationCatalog.java')
$known = [System.Collections.Generic.Dictionary[string,bool]]::new([StringComparer]::Ordinal)
foreach ($m in [regex]::Matches($catalogue, 'ids\.put\(("(?:\\.|[^"\\])*")')) {
    $known[(ConvertFrom-Json $m.Groups[1].Value)] = $true
}
$literal = '"(?:\\.|[^"\\])*"'
$expression = $literal + '(?:\s*\+\s*' + $literal + ')*'
$pattern = '(?:\btr\(|AppLanguage\.text\(\s*\w+\s*,|(?<!\.)\btext\(context,)\s*(' + $expression + ')\s*,\s*(' + $expression + ')\s*\)'
foreach ($file in Get-ChildItem (Join-Path $ProjectRoot 'app/src/main/java') -Recurse -Filter '*.java') {
    foreach ($m in [regex]::Matches((Get-Content -Raw $file.FullName), $pattern)) {
        $parts = [regex]::Matches($m.Groups[2].Value, $literal)
        $text = ($parts | ForEach-Object { ConvertFrom-Json $_.Value }) -join ''
        if (-not $known.ContainsKey($text)) { throw "Uncatalogued text in $($file.Name): $text" }
    }
}
Write-Output "All $($known.Count) bilingual messages have resource entries."
