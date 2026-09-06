cmake_minimum_required(VERSION 3.22.1)

project(sunshine_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

get_filename_component(SUNSHINE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)

include(FetchContent)

set(Boost_USE_STATIC_LIBS ON)
set(BOOST_INCLUDE_LIBRARIES
        asio
        algorithm
        atomic
        chrono
        crc
        date_time
        endian
        filesystem
        format
        log
        process
        program_options
        property_tree
        regex
        system
        thread)
set(BOOST_ENABLE_CMAKE ON)
FetchContent_Declare(
        boost
        URL https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.gz
        URL_HASH MD5=ac857d73bb754b718a039830b07b9624)
FetchContent_GetProperties(boost)
if(NOT boost_POPULATED)
    FetchContent_Populate(boost)
endif()

file(WRITE "${boost_SOURCE_DIR}/CMakeLists.txt"
        "cmake_minimum_required(VERSION 3.22)\n"
        "project(Boost VERSION 1.86.0 LANGUAGES C CXX)\n"
        "set(BOOST_SUPERPROJECT_SOURCE_DIR \"${boost_SOURCE_DIR}\")\n"
        "set(BOOST_SUPERPROJECT_VERSION \"1.86.0\")\n"
        "list(APPEND CMAKE_MODULE_PATH \"${boost_SOURCE_DIR}/tools/cmake/include\")\n"
        "include_directories(SYSTEM \"${boost_SOURCE_DIR}\")\n"
        "include(BoostRoot)\n")
set(BOOST_ENABLE_COMPATIBILITY_TARGETS ON CACHE BOOL "" FORCE)
add_subdirectory("${boost_SOURCE_DIR}" "${boost_BINARY_DIR}")

FetchContent_Declare(
        json
        URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
        URL_HASH MD5=c23a33f04786d85c29fda8d16b5f0efd)
FetchContent_MakeAvailable(json)

find_package(openssl REQUIRED CONFIG)
find_package(opus REQUIRED CONFIG)

if(NOT TARGET OpenSSL::SSL)
    add_library(OpenSSL::SSL INTERFACE IMPORTED)
    if(TARGET openssl::libssl.a)
        target_link_libraries(OpenSSL::SSL INTERFACE openssl::libssl.a)
    elseif(TARGET openssl::libssl.so)
        target_link_libraries(OpenSSL::SSL INTERFACE openssl::libssl.so)
    endif()
endif()

if(NOT TARGET OpenSSL::Crypto)
    add_library(OpenSSL::Crypto INTERFACE IMPORTED)
    if(TARGET openssl::libcrypto.a)
        target_link_libraries(OpenSSL::Crypto INTERFACE openssl::libcrypto.a)
    elseif(TARGET openssl::libcrypto.so)
        target_link_libraries(OpenSSL::Crypto INTERFACE openssl::libcrypto.so)
    endif()
endif()

if(TARGET opus::libopus.a)
    set(SUNSHINE_OPUS_TARGET opus::libopus.a)
elseif(TARGET opus::libopus.so)
    set(SUNSHINE_OPUS_TARGET opus::libopus.so)
else()
    set(SUNSHINE_OPUS_TARGET opus::opus)
endif()

set(ENET_NO_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory("${SUNSHINE_ROOT}/third-party/moonlight-common-c/enet" "${CMAKE_CURRENT_BINARY_DIR}/enet")

set(FFMPEG_ROOT "${SUNSHINE_ROOT}/third-party/build-deps/third-party/FFmpeg/FFmpeg")

add_library(simple-web-server INTERFACE)
target_include_directories(simple-web-server SYSTEM INTERFACE
        "${SUNSHINE_ROOT}/third-party/Simple-Web-Server"
        "${boost_SOURCE_DIR}")
target_compile_definitions(simple-web-server INTERFACE HAVE_OPENSSL)
target_link_libraries(simple-web-server INTERFACE Boost::boost OpenSSL::SSL OpenSSL::Crypto)

add_library(sunshine_android SHARED
        android_bridge.cpp
        android_platform.cpp
        android_rswrapper.c
        android_stubs.cpp
        android_video.cpp
        android_video_nal.cpp
        compat/wordexp_compat.cpp
        "${SUNSHINE_ROOT}/third-party/moonlight-common-c/src/RtspParser.c"
        "${SUNSHINE_ROOT}/src/audio.cpp"
        "${SUNSHINE_ROOT}/src/config.cpp"
        "${SUNSHINE_ROOT}/src/crypto.cpp"
        "${SUNSHINE_ROOT}/src/file_handler.cpp"
        "${SUNSHINE_ROOT}/src/globals.cpp"
        "${SUNSHINE_ROOT}/src/input.cpp"
        "${SUNSHINE_ROOT}/src/logging.cpp"
        "${SUNSHINE_ROOT}/src/network.cpp"
        "${SUNSHINE_ROOT}/src/nvhttp.cpp"
        "${SUNSHINE_ROOT}/src/rtsp.cpp"
        "${SUNSHINE_ROOT}/src/stat_trackers.cpp"
        "${SUNSHINE_ROOT}/src/stream.cpp")

target_include_directories(sunshine_android
        PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/compat"
        "${boost_SOURCE_DIR}"
        "${SUNSHINE_ROOT}"
        "${SUNSHINE_ROOT}/third-party"
        "${SUNSHINE_ROOT}/third-party/moonlight-common-c/enet/include"
        "${SUNSHINE_ROOT}/third-party/moonlight-common-c/src"
        "${SUNSHINE_ROOT}/third-party/nanors"
        "${SUNSHINE_ROOT}/third-party/nanors/deps/obl"
        "${SUNSHINE_ROOT}/third-party/libdisplaydevice/src/common/include"
        "${FFMPEG_ROOT}")

target_compile_definitions(sunshine_android
        PRIVATE
        BOOST_LOG_USE_NATIVE_SYSLOG
        PROJECT_NAME="Sunshine"
        PROJECT_VERSION="android"
        PROJECT_VERSION_COMMIT="android"
        SUNSHINE_ASSETS_DIR="${SUNSHINE_ROOT}/src_assets/common/assets"
        SUNSHINE_PUBLISHER_NAME="LizardByte"
        SUNSHINE_PUBLISHER_WEBSITE="https://app.lizardbyte.dev"
        SUNSHINE_PUBLISHER_ISSUE_URL="https://github.com/LizardByte/Sunshine/issues"
        SUNSHINE_TRAY=1)

target_compile_options(sunshine_android
        PRIVATE
        -Wall
        -Wno-sign-compare
        -Wno-unused-parameter
        -Wno-deprecated-declarations
        -Wno-register)

target_link_libraries(sunshine_android
        PRIVATE
        android
        jnigraphics
        log
        mediandk
        enet
        simple-web-server
        nlohmann_json::nlohmann_json
        ${SUNSHINE_OPUS_TARGET}
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::atomic
        Boost::chrono
        Boost::date_time
        Boost::filesystem
        Boost::log
        Boost::log_setup
        Boost::program_options
        Boost::regex
        Boost::system
        Boost::thread)
