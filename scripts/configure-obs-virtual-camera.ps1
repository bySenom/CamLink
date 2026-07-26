[CmdletBinding()]
param(
    [string]$HubUrl = 'rtsp://127.0.0.1:8554/camlink',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$collectionName = 'CamLink Virtual Camera'
$sceneName = 'CamLink Camera'
$sourceName = 'CamLink Hub Stream'
$obsRoot = Join-Path $env:APPDATA 'obs-studio'
$scenesDirectory = Join-Path $obsRoot 'basic\scenes'
$profilesDirectory = Join-Path $obsRoot 'basic\profiles'
$collectionPath = Join-Path $scenesDirectory "$collectionName.json"
$profilePath = Join-Path $profilesDirectory $collectionName
$profileIniPath = Join-Path $profilePath 'basic.ini'

if ((Test-Path -LiteralPath $collectionPath) -and -not $Force) {
    throw "The OBS scene collection already exists: $collectionPath. Re-run with -Force only if you want to replace the CamLink collection."
}
if ((Test-Path -LiteralPath $profileIniPath) -and -not $Force) {
    throw "The OBS profile already exists: $profileIniPath. Re-run with -Force only if you want to replace the CamLink profile."
}

New-Item -ItemType Directory -Path $scenesDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $profilePath -Force | Out-Null

$streamId = [guid]::NewGuid().ToString()
$sceneId = [guid]::NewGuid().ToString()
$transitionId = [guid]::NewGuid().ToString()
$sceneItem = [ordered]@{
    name = $sourceName
    source_uuid = $streamId
    visible = $true
    locked = $true
    rot = 0.0
    scale_ref = [ordered]@{ x = 1920.0; y = 1080.0 }
    align = 5
    bounds_type = 2
    bounds_align = 0
    bounds_crop = $false
    crop_left = 0
    crop_top = 0
    crop_right = 0
    crop_bottom = 0
    id = 1
    group_item_backup = $false
    pos = [ordered]@{ x = 0.0; y = 0.0 }
    pos_rel = [ordered]@{ x = -1.7777777910232544; y = -1.0 }
    scale = [ordered]@{ x = 1.0; y = 1.0 }
    scale_rel = [ordered]@{ x = 1.0; y = 1.0 }
    bounds = [ordered]@{ x = 1920.0; y = 1080.0 }
    bounds_rel = [ordered]@{ x = 3.555555582046509; y = 2.0 }
    scale_filter = 'lanczos'
    blend_method = 'default'
    blend_type = 'normal'
    show_transition = [ordered]@{ duration = 0 }
    hide_transition = [ordered]@{ duration = 0 }
    private_settings = [ordered]@{}
}

$streamSource = [ordered]@{
    name = $sourceName
    uuid = $streamId
    id = 'ffmpeg_source'
    versioned_id = 'ffmpeg_source'
    settings = [ordered]@{
        is_local_file = $false
        input = $HubUrl
        input_format = ''
        input_options = 'rtsp_transport=tcp'
        looping = $false
        restart_on_activate = $true
        close_when_inactive = $false
        clear_on_media_end = $false
        hw_decode = $true
    }
    mixers = 0
    sync = 0
    flags = 0
    volume = 1.0
    balance = 0.5
    enabled = $true
    muted = $true
    push_to_mute = $false
    push_to_mute_delay = 0
    push_to_talk = $false
    push_to_talk_delay = 0
    hotkeys = [ordered]@{}
    deinterlace_mode = 0
    deinterlace_field_order = 0
    monitoring_type = 0
    private_settings = [ordered]@{}
}

$sceneSource = [ordered]@{
    name = $sceneName
    uuid = $sceneId
    id = 'scene'
    versioned_id = 'scene'
    settings = [ordered]@{
        id_counter = 1
        custom_size = $false
        items = @($sceneItem)
    }
    mixers = 0
    sync = 0
    flags = 0
    volume = 1.0
    balance = 0.5
    enabled = $true
    muted = $false
    push_to_mute = $false
    push_to_mute_delay = 0
    push_to_talk = $false
    push_to_talk_delay = 0
    hotkeys = [ordered]@{ 'OBSBasic.SelectScene' = @(); 'libobs.show_scene_item.1' = @(); 'libobs.hide_scene_item.1' = @() }
    deinterlace_mode = 0
    deinterlace_field_order = 0
    monitoring_type = 0
    private_settings = [ordered]@{}
}

$collection = [ordered]@{
    current_scene = $sceneName
    current_program_scene = $sceneName
    scene_order = @([ordered]@{ name = $sceneName })
    name = $collectionName
    sources = @($sceneSource, $streamSource)
    groups = @()
    quick_transitions = @(
        [ordered]@{ name = 'Cut'; duration = 300; hotkeys = @(); id = 1; fade_to_black = $false },
        [ordered]@{ name = 'Fade'; duration = 300; hotkeys = @(); id = 2; fade_to_black = $false }
    )
    transitions = @([ordered]@{ name = 'Fade'; uuid = $transitionId; id = 'fade_transition'; versioned_id = 'fade_transition'; settings = [ordered]@{}; private_settings = [ordered]@{} })
    saved_projectors = @()
    canvases = @()
    current_transition = 'Fade'
    transition_duration = 300
    preview_locked = $false
    scaling_enabled = $false
    scaling_level = -4
    scaling_off_x = 0.0
    scaling_off_y = 0.0
    modules = [ordered]@{}
    resolution = [ordered]@{ x = 1920; y = 1080 }
    version = 2
}

$profile = @'
[General]
Name=CamLink Virtual Camera

[Video]
BaseCX=1920
BaseCY=1080
OutputCX=1920
OutputCY=1080
FPSType=0
FPSCommon=60
FPSInt=60
FPSNum=60
FPSDen=1
ScaleType=lanczos
ColorFormat=NV12
ColorSpace=709
ColorRange=Partial

[Audio]
SampleRate=48000
ChannelSetup=Stereo
'@

$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($collectionPath, ($collection | ConvertTo-Json -Depth 12), $utf8)
[System.IO.File]::WriteAllText($profileIniPath, $profile, $utf8)

Write-Host "Created OBS scene collection: $collectionPath"
Write-Host "Created OBS profile: $profileIniPath"
Write-Host "Start OBS with: --collection `"$collectionName`" --profile `"$collectionName`" --scene `"$sceneName`" --startvirtualcam --minimize-to-tray"
