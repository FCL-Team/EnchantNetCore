#include <jni.h>
#include <vector>
#include "easytier_libs/easytier.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_org_fcl_enchantnetcore_easytier_NativeBridge_parseConfig
        (JNIEnv* env, jclass, jstring cfg) {
    const char* c_cfg = cfg ? env->GetStringUTFChars(cfg, nullptr) : nullptr;
    int ret = parse_config(c_cfg);
    if (c_cfg) env->ReleaseStringUTFChars(cfg, c_cfg);
    return ret;
}

JNIEXPORT jint JNICALL
Java_org_fcl_enchantnetcore_easytier_NativeBridge_runNetworkInstance
        (JNIEnv* env, jclass, jstring cfg) {
    const char* c_cfg = cfg ? env->GetStringUTFChars(cfg, nullptr) : nullptr;
    int ret = run_network_instance(c_cfg);
    if (c_cfg) env->ReleaseStringUTFChars(cfg, c_cfg);
    return ret;
}

JNIEXPORT jint JNICALL
Java_org_fcl_enchantnetcore_easytier_NativeBridge_setTunFd
        (JNIEnv* env, jclass, jstring instName, jint fd) {
    const char* c_name = instName ? env->GetStringUTFChars(instName, nullptr) : nullptr;
    int32_t ret = set_tun_fd(c_name, fd);
    if (c_name) env->ReleaseStringUTFChars(instName, c_name);
    return ret;
}

JNIEXPORT jint JNICALL
Java_org_fcl_enchantnetcore_easytier_NativeBridge_retainNetworkInstance
        (JNIEnv* env, jclass, jobjectArray names) {
    jsize n = names ? env->GetArrayLength(names) : 0;
    std::vector<jstring> jnames(n);
    std::vector<const char*> c_names(n);

    for (jsize i = 0; i < n; ++i) {
        auto s = (jstring)env->GetObjectArrayElement(names, i);
        jnames[i] = s;
        c_names[i] = s ? env->GetStringUTFChars(s, nullptr) : nullptr;
    }

    int ret = retain_network_instance(n ? c_names.data() : nullptr, (size_t)n);

    for (jsize i = 0; i < n; ++i) {
        if (jnames[i] && c_names[i]) {
            env->ReleaseStringUTFChars(jnames[i], c_names[i]);
        }
        if (jnames[i]) env->DeleteLocalRef(jnames[i]);
    }
    return ret;
}

JNIEXPORT jobjectArray JNICALL
Java_org_fcl_enchantnetcore_easytier_NativeBridge_getNetworkInfos
        (JNIEnv* env, jclass, jint maxLen) {
    size_t cap = (maxLen > 0 ? (size_t)maxLen : 256);
    std::vector<KeyValuePair> buf(cap);

    int count = collect_network_infos(buf.data(), buf.size());
    if (count <= 0) {
        jclass cls = env->FindClass("org/fcl/enchantnetcore/easytier/NativeBridge$NetworkInfo");
        return env->NewObjectArray(0, cls, nullptr);
    }

    jclass cls = env->FindClass("org/fcl/enchantnetcore/easytier/NativeBridge$NetworkInfo");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    jobjectArray arr = env->NewObjectArray(count, cls, nullptr);

    for (int i = 0; i < count; ++i) {
        jstring jkey = buf[i].key ? env->NewStringUTF(buf[i].key) : nullptr;
        jstring jval = buf[i].value ? env->NewStringUTF(buf[i].value) : nullptr;
        jobject item = env->NewObject(cls, ctor, jkey, jval);
        env->SetObjectArrayElement(arr, i, item);

        if (buf[i].key)   free_string(buf[i].key);
        if (buf[i].value) free_string(buf[i].value);

        if (jkey) env->DeleteLocalRef(jkey);
        if (jval) env->DeleteLocalRef(jval);
        env->DeleteLocalRef(item);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_org_fcl_enchantnetcore_easytier_NativeBridge_getLastError
        (JNIEnv* env, jclass) {
    const char* err = nullptr;
    get_error_msg(&err);
    if (!err) return nullptr;
    jstring jerr = env->NewStringUTF(err);
    free_string(err);
    return jerr;
}

}