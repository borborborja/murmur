# llama.cpp i whisper.cpp amb Ninja a Windows, en comptes de MSBuild, per a les compilacions de la
# CI que ho necessiten:
#   - Windows ARM: el crate cmake força les eines x64 al generador de Visual Studio (-Thost=x64), i
#     l'executor ARM no en té. A més, hi compilem amb clang-cl (vegeu .github/actions/preparar).
#   - Vulkan: amb MSBuild, el generador dels shaders de ggml-vulkan (un ExternalProject) té camins
#     massa llargs per al seguiment de fitxers (FTK1011), i sense seguiment es salta la compilació.
#
# Deixa a l'entorn de les feines següents el de Visual Studio per a l'arquitectura (INCLUDE, LIB,
# PATH…), que Ninja necessita, menys VCINSTALLDIR i VSCMD_*: amb aquestes, Rust busca link.exe al
# PATH, i a bash hi troba el de Git. Sense, el troba ell mateix amb vswhere, com sempre.
#
#   pwsh .github/scripts/msvc-ninja.ps1 x64|arm64
param([Parameter(Mandatory)][ValidateSet("x64", "arm64")][string]$Arch)

if (-not (Get-Command ninja -ErrorAction SilentlyContinue)) { choco install ninja -y --no-progress }
$vs = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath
$before = @{}
Get-ChildItem env: | ForEach-Object { $before[$_.Name] = $_.Value }
cmd /c "`"$vs\VC\Auxiliary\Build\vcvarsall.bat`" $Arch >nul && set" | ForEach-Object {
  if ($_ -match '^([^=]+)=(.*)$') {
    $name, $value = $matches[1], $matches[2]
    if ($name -eq "VCINSTALLDIR" -or $name -like "VSCMD_*" -or $before[$name] -eq $value) { return }
    "$name=$value" | Out-File -FilePath $env:GITHUB_ENV -Append
  }
}
# CMake hi posaria /EHsc, però el crate cmake substitueix els seus flags pels de cc, que no el
# porten: cl.exe només avisa, però clang-cl no compila els try i throw de llama.cpp. Amb guió i no
# amb barra: Git Bash converteix «/EHsc» en un camí (C:/Program Files/Git/EHsc).
@("CMAKE_GENERATOR=Ninja", "CXXFLAGS=-EHsc") | Out-File -FilePath $env:GITHUB_ENV -Append
