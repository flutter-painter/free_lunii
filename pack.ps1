# Import one story folder into the STUdio library.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Show-Help {
    Write-Host @'
Put one story into the STUdio library.

  .\pack.ps1 "stories\My story"

That folder contains:

  audio.mp3     the story
  cover.png     the picture shown with the title
  title.mp3     optional spoken title (WAV and Ogg work too)

The picture stays up for the title. The story itself has no picture, so the Lunii turns the screen off while it plays.

Other files, from this folder:

  .\pack.ps1 --audio FILE --image FILE --title TEXT [--title-audio FILE]
'@
}

if ($args.Count -eq 0 -or $args[0] -in @('-h', '--help', '/?')) {
    Show-Help
    if ($args.Count -eq 0) { exit 1 }
    exit 0
}

$audio = $null
$image = $null
$title = $null
$titleAudio = $null
if ($args.Count -eq 1 -and $args[0] -notlike '-*') {
    $story = $args[0]
    if (-not [System.IO.Path]::IsPathRooted($story)) {
        $story = Join-Path $root $story
    }
    $audio = Join-Path $story 'audio.mp3'
    $image = Join-Path $story 'cover.png'
    $title = Split-Path -Leaf $story
    foreach ($ext in @('.mp3', '.wav', '.ogg')) {
        $candidate = Join-Path $story ("title" + $ext)
        if (Test-Path -LiteralPath $candidate) {
            $titleAudio = $candidate
            break
        }
    }
} else {
    for ($i = 0; $i -lt $args.Count; $i++) {
        $value = if ($i + 1 -lt $args.Count) { $args[$i + 1] } else { $null }
        switch ($args[$i]) {
            '--audio' { $audio = $value; $i++ }
            '--image' { $image = $value; $i++ }
            '--title' { $title = $value; $i++ }
            '--title-audio' { $titleAudio = $value; $i++ }
            default { Write-Host "Unknown option: $($args[$i])"; exit 1 }
        }
    }
}
if (-not $audio -or -not $image -or -not $title) {
    Write-Host 'Need a story folder, or --audio, --image, and --title.'
    exit 1
}
if (-not (Test-Path -LiteralPath $audio)) { Write-Host "Missing $audio"; exit 1 }
if (-not (Test-Path -LiteralPath $image)) { Write-Host "Missing $image"; exit 1 }
if ($titleAudio -and -not (Test-Path -LiteralPath $titleAudio)) { Write-Host "Missing $titleAudio"; exit 1 }

if (-not (Test-Path (Join-Path $root 'studio\pom.xml'))) {
    Write-Host 'Cloning STUdio'
    git clone --depth 1 https://github.com/marian-m12l/studio.git (Join-Path $root 'studio')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$mvnHome = Join-Path $root 'tools\apache-maven-3.9.9'
$mvn = Join-Path $mvnHome 'bin\mvn.cmd'
if (-not (Test-Path $mvn)) {
    Write-Host 'Downloading Maven 3.9.9'
    $tools = Join-Path $root 'tools'
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $zip = Join-Path $tools 'maven.zip'
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $tools -Force
    Remove-Item $zip
}

$coreJar = Join-Path $root 'studio\core\target\studio-core-0.4.3-SNAPSHOT.jar'
if (-not (Test-Path $coreJar)) {
    Write-Host 'Building STUdio core'
    & $mvn -B -f (Join-Path $root 'studio\pom.xml') -pl core -am install "-DskipTests"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host 'Importing the story into the STUdio library'
$env:MAVEN_OPTS = '-Xmx2g'
$env:FREE_LUNII_AUDIO = $audio
$env:FREE_LUNII_IMAGE = $image
$env:FREE_LUNII_TITLE = $title
if ($titleAudio) { $env:FREE_LUNII_TITLE_AUDIO = $titleAudio }
else { Remove-Item Env:FREE_LUNII_TITLE_AUDIO -ErrorAction SilentlyContinue }
& $mvn -B -f (Join-Path $root 'importer\pom.xml') compile exec:java
exit $LASTEXITCODE
