/*
 * JNI surface of libthreadsbf: my.github.MrxSiN.threadshideads.bridge.NativeCore.
 *
 * Host callbacks go to one Java object (NativeCore) through cached method
 * ids. Level buffers are exposed to Java once, as direct ByteBuffers over the
 * engine's own memory, so no callback allocates.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "bf_engine.h"

#define MORE_FLAG 0x40000000

typedef struct {
    bf_engine engine;
    JNIEnv *env;       /* valid for the duration of one request */
    jobject host;      /* global ref */
    jmethodID capability;
    jmethodID fetch;
    jmethodID more;
} jni_core;

static int finish_call(JNIEnv *env, jint result, int *more)
{
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -1;
    }
    if (result < 0) {
        return -1;
    }
    *more = (result & MORE_FLAG) != 0;
    return result & ~MORE_FLAG;
}

static int host_capability(void *ctx, int level, const uint8_t *req, size_t req_len,
                           uint8_t *dst, size_t cap, int *more)
{
    jni_core *core = (jni_core *) ctx;
    JNIEnv *env = core->env;
    jint result = (*env)->CallIntMethod(env, core->host, core->capability, (jint) level,
                                        (jint) (req - core->engine.levels[level].out), (jint) req_len,
                                        (jint) (dst - core->engine.levels[level].in), (jint) cap);
    return finish_call(env, result, more);
}

static int host_fetch(void *ctx, int level, uint32_t handle, int source, int transform,
                      uint8_t *dst, size_t cap, int *more)
{
    jni_core *core = (jni_core *) ctx;
    JNIEnv *env = core->env;
    jint result = (*env)->CallIntMethod(env, core->host, core->fetch, (jint) level, (jint) handle,
                                        (jint) source, (jint) transform,
                                        (jint) (dst - core->engine.levels[level].in), (jint) cap);
    return finish_call(env, result, more);
}

static int host_more(void *ctx, int level, uint8_t *dst, size_t cap, int *more)
{
    jni_core *core = (jni_core *) ctx;
    JNIEnv *env = core->env;
    jint result = (*env)->CallIntMethod(env, core->host, core->more, (jint) level,
                                        (jint) (dst - core->engine.levels[level].in), (jint) cap);
    return finish_call(env, result, more);
}

JNIEXPORT jlong JNICALL
Java_my_github_MrxSiN_threadshideads_bridge_NativeCore_nativeCreate(JNIEnv *env, jclass clazz, jobject host)
{
    jni_core *core = (jni_core *) calloc(1, sizeof *core);
    if (core == NULL) {
        return 0;
    }
    jclass type = (*env)->GetObjectClass(env, host);
    core->capability = (*env)->GetMethodID(env, type, "onCapability", "(IIIII)I");
    core->fetch = (*env)->GetMethodID(env, type, "onFetch", "(IIIIII)I");
    core->more = (*env)->GetMethodID(env, type, "onMore", "(III)I");
    if (core->capability == NULL || core->fetch == NULL || core->more == NULL) {
        free(core);
        return 0;
    }
    core->host = (*env)->NewGlobalRef(env, host);
    bf_host callbacks = {host_capability, host_fetch, host_more, core};
    bf_engine_init(&core->engine, &callbacks);
    return (jlong) (intptr_t) core;
}

JNIEXPORT jobject JNICALL
Java_my_github_MrxSiN_threadshideads_bridge_NativeCore_nativeBuffer(JNIEnv *env, jclass clazz, jlong handle,
                                                                   jint level, jboolean input)
{
    jni_core *core = (jni_core *) (intptr_t) handle;
    if (core == NULL || level < 0 || level >= BF_MAX_LEVELS) {
        return NULL;
    }
    bf_level *l = &core->engine.levels[level];
    return input ? (*env)->NewDirectByteBuffer(env, l->in, BF_LEVEL_IN_CAP)
                 : (*env)->NewDirectByteBuffer(env, l->out, BF_LEVEL_OUT_CAP);
}

JNIEXPORT jint JNICALL
Java_my_github_MrxSiN_threadshideads_bridge_NativeCore_nativeRequest(JNIEnv *env, jclass clazz, jlong handle,
                                                                    jint program, jobject request, jint request_len,
                                                                    jobject response)
{
    jni_core *core = (jni_core *) (intptr_t) handle;
    uint8_t *req = (uint8_t *) (*env)->GetDirectBufferAddress(env, request);
    uint8_t *out = (uint8_t *) (*env)->GetDirectBufferAddress(env, response);
    jlong out_cap = (*env)->GetDirectBufferCapacity(env, response);
    if (core == NULL || req == NULL || out == NULL || request_len < 0) {
        return -BF_ERR_PROGRAM;
    }
    JNIEnv *previous = core->env;
    core->env = env;
    size_t out_len = 0;
    int status = bf_engine_request(&core->engine, program, req, (size_t) request_len, out,
                                   (size_t) out_cap, &out_len);
    core->env = previous;
    return status == BF_OK ? (jint) out_len : -status;
}

JNIEXPORT jint JNICALL
Java_my_github_MrxSiN_threadshideads_bridge_NativeCore_nativeRoute(JNIEnv *env, jclass clazz, jint opcode)
{
    return bf_route(opcode);
}

JNIEXPORT void JNICALL
Java_my_github_MrxSiN_threadshideads_bridge_NativeCore_nativeReset(JNIEnv *env, jclass clazz, jlong handle)
{
    jni_core *core = (jni_core *) (intptr_t) handle;
    if (core != NULL) {
        bf_engine_reset(&core->engine);
    }
}

JNIEXPORT jlong JNICALL
Java_my_github_MrxSiN_threadshideads_bridge_NativeCore_nativeCounters(JNIEnv *env, jclass clazz, jlong handle)
{
    jni_core *core = (jni_core *) (intptr_t) handle;
    if (core == NULL) {
        return 0;
    }
    return ((jlong) core->engine.calls << 32) | (jlong) core->engine.host_calls;
}
