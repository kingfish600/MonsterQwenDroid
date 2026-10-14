# package_runtime.cmake — copy every runtime shared object produced by the
# llama.cpp subbuild into the APK jniLibs staging directory.
#
# llama-server links dynamically against the fork's own libs
# (libllama-common.so, libllama-server-impl.so, ggml backend libs); they all
# must ship inside the APK so LD_LIBRARY_PATH=<nativeLibraryDir> resolves them.

file(GLOB BONSAI_RUNTIME_LIBS "${SRC}/*")

foreach(f ${BONSAI_RUNTIME_LIBS})
    get_filename_component(name "${f}" NAME)
    # shared objects only: lib*.so*, never the plain executables
    if(name MATCHES "^lib.+\\.so(\\.[0-9]+)*$")
        file(COPY_FILE "${f}" "${DST}/${name}")
        message(STATUS "bonsai-pack: ${name}")
    endif()
endforeach()
