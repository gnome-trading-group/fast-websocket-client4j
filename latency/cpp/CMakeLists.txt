cmake_minimum_required(VERSION 3.10)

project(TestBoost)

set (CMAKE_CXX_STANDARD 17)

find_package(Boost)

if(Boost_FOUND)
  include_directories(${Boost_INCLUDE_DIRS})
  add_executable(TestBoost boost_test.cpp)
endif()
