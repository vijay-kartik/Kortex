#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaState {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    
    ~LlamaState() {
        if (ctx) llama_free(ctx);
        if (model) llama_free_model(model);
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_dev_kortex_core_llm_LlamaCppProvider_loadModelNative(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Loading model: %s", path);
    
    llama_backend_init();
    
    auto mparams = llama_model_default_params();
    llama_model* model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    
    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }
    
    auto cparams = llama_context_default_params();
    cparams.n_ctx = 2048; // Hardcoded context size for now
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    
    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        return 0;
    }
    
    LlamaState* state = new LlamaState{model, ctx};
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_kortex_core_llm_LlamaCppProvider_generateNative(JNIEnv* env, jobject, jlong ptr, jstring jprompt) {
    LlamaState* state = reinterpret_cast<LlamaState*>(ptr);
    if (!state || !state->ctx || !state->model) {
        return env->NewStringUTF("");
    }
    
    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    LOGI("Generating for prompt: %s", prompt);
    
    // Very basic generation loop placeholder
    // A proper implementation requires tokenization, sampler initialization, 
    // context batch decoding, and token-to-string mapping.
    // For now, we return a dummy string to prove the JNI bridge compiles.
    std::string response = "Hello from llama.cpp JNI! A proper inference loop needs to be implemented here.";
    
    env->ReleaseStringUTFChars(jprompt, prompt);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kortex_core_llm_LlamaCppProvider_closeModelNative(JNIEnv* env, jobject, jlong ptr) {
    LlamaState* state = reinterpret_cast<LlamaState*>(ptr);
    if (state) {
        delete state;
    }
    llama_backend_free();
}
