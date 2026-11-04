# ##############################################################################
# apps/examples/ai_agent/CMakeLists.txt
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to you under the Apache License, Version
# 2.0 (the "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.  See the License for the specific language governing
# permissions and limitations under the License.
#
# ##############################################################################

if(CONFIG_EXAMPLES_AI_AGENT)

  if(CONFIG_TFLITEMICRO)
    # velaAI 端侧语言模型 (TFLite Micro): 模型/分词器/推理模块
    set(AI_AGENT_LOCAL_LM_SRCS
      ai_lm.cxx
      tokenizer.c
      speech/speech_feat.c
      speech/cmd_asr.cxx
      speech/mic_capture.c
      speech/voice_question.c)
    set(AI_AGENT_LOCAL_LM_DEPS tflite_micro)
    set(AI_AGENT_LOCAL_LM_DEFS
      TFLITE_WITH_STABLE_ABI=0
      TFLITE_USE_OPAQUE_DELEGATE=0
      TFLITE_SINGLE_ROUNDING=0
      TF_LITE_STRIP_ERROR_STRINGS
      TF_LITE_STATIC_MEMORY)
  endif()

  nuttx_add_application(
    NAME
    ${CONFIG_EXAMPLES_AI_AGENT_PROGNAME}
    PRIORITY
    ${CONFIG_EXAMPLES_AI_AGENT_PRIORITY}
    STACKSIZE
    ${CONFIG_EXAMPLES_AI_AGENT_STACKSIZE}
    MODULE
    ${CONFIG_EXAMPLES_AI_AGENT}
    DEPENDS
    lvgl
    ${AI_AGENT_LOCAL_LM_DEPS}
    SRCS
    ai_agent_main.c
    chat_log.c
    ui/launcher_page.c
    ui/pet_page.c
    ui/settings_page.c
    ui/about_page.c
    ui/ui.c
    ui/assets/img_background_watch.c
    ui/assets/pet_cloud_open.c
    ui/assets/pet_cloud_blink.c
    ui/assets/ui_font_cjk_18.c
    ui/assets/ui_font_sans_16.c
    ui/assets/ui_font_sans_16_bold.c
    ui/assets/ui_font_sans_24.c
    ui/assets/ui_font_sans_32.c
    ui/assets/ui_font_sans_64.c
    ui/assets/ui_font_sans_72.c
    ui/assets/ui_font_sans_88.c
    ui/assets/ui_font_sans_88_bold.c
    ${AI_AGENT_LOCAL_LM_SRCS}
    DEFINITIONS
    ${AI_AGENT_LOCAL_LM_DEFS}
    COMPILE_FLAGS
    -Wno-error)

endif()

# ##############################################################################
# audio_setup - 注册 SF32LB52 板载音频设备 (/dev/audio0, 麦克风采集)
# ##############################################################################

# SiFli HAL 头文件 (与 arch 构建一致) —— audio_setup / audio_beep 共用,
# 放在 if() 之外以免依赖 CONFIG_EXAMPLES_AUDIO_SETUP 的顺序
set(SIFLI_VENDOR_DIR ${CMAKE_SOURCE_DIR}/../vendor/sifli)
set(SIFLI_HAL_INCLUDES
  ${SIFLI_VENDOR_DIR}/chips/drivers/Include
  ${SIFLI_VENDOR_DIR}/chips/drivers/cmsis/Include
  ${SIFLI_VENDOR_DIR}/chips/drivers/cmsis/sf32lb52x
  ${SIFLI_VENDOR_DIR}/chips/boards/include/config/sf32lb52x
  ${SIFLI_VENDOR_DIR}/chips/sf32lb52
  ${SIFLI_VENDOR_DIR}/chips/external/CMSIS/Include)

if(CONFIG_EXAMPLES_AUDIO_SETUP)

  nuttx_add_application(
    NAME
    audio_setup
    PRIORITY
    100
    STACKSIZE
    4096
    MODULE
    ${CONFIG_EXAMPLES_AUDIO_SETUP}
    SRCS
    audio/audio_setup_main.c
    audio/sf32lb52_audcodec.c
    audio/sf32lb52_audio_tx.c
    COMPILE_FLAGS
    -Wno-error)

  target_compile_definitions(apps_audio_setup PRIVATE
    SOC_BF0_HCPU
    SF32LB52X
    HAL_TICK_PER_SECOND=1000
    USE_HAL_DRIVER)
  target_include_directories(apps_audio_setup PRIVATE
    ${SIFLI_HAL_INCLUDES}
    ${CMAKE_CURRENT_LIST_DIR})

endif()

# ##############################################################################
# audio_rec_test - 独立麦克风采集验证程序
# 绕过 nxrecorder 直接驱动 /dev/audio/audio0, 用于验证 D-cache 修复后
# "无人说话时基线不恒零" 这一验收口径 (docs_ble/25 §25.4)。
# 仅测试用, 验证完可随 CONFIG_EXAMPLES_AUDIO_REC_TEST 一起移除。
# ##############################################################################

if(CONFIG_EXAMPLES_AUDIO_REC_TEST)

  nuttx_add_application(
    NAME
    audio_rec_test
    PRIORITY
    100
    STACKSIZE
    8192
    MODULE
    ${CONFIG_EXAMPLES_AUDIO_REC_TEST}
    SRCS
    audio/audio_rec_test_main.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_audio_rec_test PRIVATE
    ${CMAKE_CURRENT_LIST_DIR})

endif()

# ##############################################################################
# audio_beep - 扬声器(蜂鸣)最小验证: audcodec DAC + 板载 Class-D PA(PA10)
# 不走 NuttX audio 框架 (驱动目前只有采集 RX 路径), 直接调 SiFli HAL。
# ##############################################################################

# ##############################################################################
# audio_play_test - 播放验收 (NuttX audio 框架 -> AUDPRC -> AUDCODEC -> PA)
# ##############################################################################

# ##############################################################################
# voice_speak - 从 NSH 触发一次完整 TTS -> 嘴 -> 喇叭 的说话链路
# ##############################################################################

if(CONFIG_EXAMPLES_VOICE_SPEAK)

  nuttx_add_application(
    NAME
    voice_speak
    PRIORITY
    100
    STACKSIZE
    8192
    MODULE
    ${CONFIG_EXAMPLES_VOICE_SPEAK}
    SRCS
    speech/voice_speak_main.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_voice_speak PRIVATE
    ${CMAKE_CURRENT_LIST_DIR})

endif()

if(CONFIG_EXAMPLES_PCM_MEDIA_TEST)

  nuttx_add_application(
    NAME
    pcm_media_test
    PRIORITY
    100
    STACKSIZE
    8192
    MODULE
    ${CONFIG_EXAMPLES_PCM_MEDIA_TEST}
    SRCS
    audio/pcm_media_test_main.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_pcm_media_test PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${CMAKE_SOURCE_DIR}/../frameworks/multimedia/media/include)
  target_link_libraries(apps_pcm_media_test PRIVATE m)

endif()

if(CONFIG_EXAMPLES_AUDIO_PLAY)
  nuttx_add_application(
    NAME
    audio_play
    PRIORITY
    100
    STACKSIZE
    8192
    MODULE
    ${CONFIG_EXAMPLES_AUDIO_PLAY}
    SRCS
    audio/audio_play_main.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_audio_play PRIVATE
    ${CMAKE_CURRENT_LIST_DIR})

endif()

if(CONFIG_EXAMPLES_AUDIO_PLAY_TEST)

  nuttx_add_application(
    NAME
    audio_play_test
    PRIORITY
    100
    STACKSIZE
    8192
    MODULE
    ${CONFIG_EXAMPLES_AUDIO_PLAY_TEST}
    SRCS
    audio/audio_play_test_main.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_audio_play_test PRIVATE
    ${CMAKE_CURRENT_LIST_DIR})

endif()

# ##############################################################################
# hr_sensor - MAX30102 心率 (用户态驱动 + 算法, 发布 algo_heartrate)
# ##############################################################################

if(CONFIG_EXAMPLES_SENSOR_MAX30102)

  nuttx_add_application(
    NAME
    hr_sensor
    PRIORITY
    100
    STACKSIZE
    8192
    MODULE
    ${CONFIG_EXAMPLES_SENSOR_MAX30102}
    SRCS
    sensors/max30102.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_hr_sensor PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${CMAKE_SOURCE_DIR}/system/uorb
    ${SIFLI_HAL_INCLUDES})
  target_link_libraries(apps_hr_sensor PRIVATE uorb)

endif()

# ##############################################################################
# imu - 读 /dev/mpu0 原始六轴数据（调试命令）
#   板子上没有现成的 IMU 读取工具，而 i2ctool 在这个构建里对任何总线都
#   返回 "Transfer failed: 1"（实测连 MAX30102 那条也不行），所以自己读。
# ##############################################################################

if(CONFIG_SENSORS_MPU60X0)

  nuttx_add_application(
    NAME
    imu
    PRIORITY
    100
    STACKSIZE
    4096
    MODULE
    ${CONFIG_SENSORS_MPU60X0}
    SRCS
    sensors/imu_test.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_imu PRIVATE
    ${CMAKE_CURRENT_LIST_DIR})

endif()


if(CONFIG_EXAMPLES_AUDIO_BEEP)

  nuttx_add_application(
    NAME
    audio_beep
    PRIORITY
    100
    STACKSIZE
    4096
    MODULE
    ${CONFIG_EXAMPLES_AUDIO_BEEP}
    SRCS
    audio/audio_beep_main.c
    COMPILE_FLAGS
    -Wno-error)

  target_compile_definitions(apps_audio_beep PRIVATE
    SOC_BF0_HCPU
    SF32LB52X
    HAL_TICK_PER_SECOND=1000
    USE_HAL_DRIVER)
  target_include_directories(apps_audio_beep PRIVATE
    ${SIFLI_HAL_INCLUDES}
    ${CMAKE_CURRENT_LIST_DIR})

endif()

# ##############################################################################
# tts_stress - TTS 播报压测(取证工具): 向出站队列投 channel=voice 消息,
# 由 ai_agent 守护进程 outbound 线程真实走 TTS->播放, 用于复现 2026-10-24
# 的 audcodec_tx apb 断言(v49 修正后未复现验证)。详见 debug/tts_stress.c
# 文件头。用法: tts_stress [次数] [间隔秒], 默认 10 5。
# 无 Kconfig 门槛, 始终注册; 只依赖 message_bus(纯 pthread 实现),
# 链接依赖由镜像里的 ai_agent 守护进程(CONFIG_EXAMPLES_AI_AGENT_VELA)提供。
# ##############################################################################

if(TRUE)

  nuttx_add_application(
    NAME
    tts_stress
    PRIORITY
    100
    STACKSIZE
    16384
    SRCS
    debug/tts_stress.c
    COMPILE_FLAGS
    -Wno-error)

  # message_bus.h(src/core) 与它包含的 agent_compat.h(include/) ——
  # 与 ai_agent 守护进程同款的一对 include 根
  target_include_directories(apps_tts_stress PRIVATE
    ${CMAKE_SOURCE_DIR}/../packages/ai_agent/src
    ${CMAKE_SOURCE_DIR}/../packages/ai_agent/include)

endif()
# ##############################################################################
# care_test - 「小云主动关怀」验收/诊断(NSH): 查静止时长/步数/阈值,
# 临时把久坐阈值改小(仍走真实判定路径), 或立刻弹一条 / 让步数里程碑
# 在下一次 tick 触发。详见 debug/care_test.c 文件头。
# 链接依赖(pet_care_* / pedometer_get_*_ )由镜像里的 ai_agent 守护进程提供。
# ##############################################################################

if(TRUE)

  nuttx_add_application(
    NAME
    care_test
    PRIORITY
    100
    STACKSIZE
    4096
    SRCS
    debug/care_test.c
    COMPILE_FLAGS
    -Wno-error)

  target_include_directories(apps_care_test PRIVATE
    ${CMAKE_SOURCE_DIR}/../packages/ai_agent/src
    ${CMAKE_SOURCE_DIR}/../packages/ai_agent/include)

endif()

