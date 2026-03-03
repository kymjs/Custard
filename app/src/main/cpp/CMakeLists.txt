cmake_minimum_required(VERSION 3.22.1)

project(streamnative)

add_library(
        streamnative
        SHARED
        streamnative/native_xml_splitter.cpp
        streamnative/native_markdown_splitter.cpp
        streamnative/StreamOperators.cpp
        streamnative/plugins/StreamXmlPlugin.cpp
        streamnative/plugins/BaseJsonPlugin.cpp
        streamnative/plugins/StreamJsonPlugin.cpp
        streamnative/plugins/StreamPureJsonPlugin.cpp
        streamnative/plugins/StreamPlanExecutionPlugin.cpp
        streamnative/plugins/StreamMarkdownPlugin.cpp
        streamnative/StreamBuilders.cpp
        streamnative/HotStream.cpp
        streamnative/StringExtensions.cpp
)

target_include_directories(
        streamnative
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(
        log-lib
        log
)

target_link_libraries(
        streamnative
        ${log-lib}
)
