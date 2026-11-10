$src = $args[0]
$out = $args[1]
$size = 120000
$bytes = [System.IO.File]::ReadAllBytes($src)
$n = [math]::Ceiling($bytes.Length / $size)
Get-ChildItem "$out.part*" -ErrorAction SilentlyContinue | Remove-Item -Force
for ($i = 0; $i -lt $n; $i++) {
  $start = $i * $size
  $len = [math]::Min($size, $bytes.Length - $start)
  $buf = New-Object byte[] $len
  [Array]::Copy($bytes, $start, $buf, 0, $len)
  [System.IO.File]::WriteAllBytes(("{0}.part{1:D2}" -f $out, $i), $buf)
}
Write-Output ("parts=" + $n + " total=" + $bytes.Length)
