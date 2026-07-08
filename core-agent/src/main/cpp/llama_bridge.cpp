#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <chrono>
#include "llama.h"

#define TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaState {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    
    ~LlamaState() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_dev_kortex_core_llm_LlamaCppProvider_loadModelNative(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Loading model: %s", path);
    
    llama_backend_init();
    
    auto mparams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    
    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }
    
    auto cparams = llama_context_default_params();
    cparams.n_ctx = 2048; // Hardcoded context size for now
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    cparams.n_batch = 2048;
    llama_context* ctx = llama_init_from_model(model, cparams);
    
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }
    
    LlamaState* state = new LlamaState{model, ctx};
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_kortex_core_llm_LlamaCppProvider_generateNative(JNIEnv* env, jobject, jlong ptr, jstring jprompt, jobject jcallback) {
    LlamaState* state = reinterpret_cast<LlamaState*>(ptr);
    if (!state || !state->ctx || !state->model) {
        return env->NewStringUTF("");
    }
    
    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    LOGI("Generating for prompt: %s", prompt);
    
    jmethodID onTokenMethod = nullptr;
    if (jcallback) {
        jclass cbClass = env->GetObjectClass(jcallback);
        onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }
    
    const llama_vocab* vocab = llama_model_get_vocab(state->model);
    
    // 1. Tokenize prompt
    int prompt_len = strlen(prompt);
    int max_tokens = prompt_len + 128; // safe guess
    std::vector<llama_token> prompt_tokens(max_tokens);
    int n_prompt = llama_tokenize(vocab, prompt, prompt_len, prompt_tokens.data(), prompt_tokens.size(), true, true);
    if (n_prompt < 0) {
        // resize and try again
        prompt_tokens.resize(-n_prompt);
        n_prompt = llama_tokenize(vocab, prompt, prompt_len, prompt_tokens.data(), prompt_tokens.size(), true, true);
    }
    if (n_prompt < 0) {
        LOGE("Failed to tokenize");
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("");
    }
    prompt_tokens.resize(n_prompt);
    
    // Clear previous KV cache so we can run multiple queries independently
    llama_memory_clear(llama_get_memory(state->ctx), true);
    
    // 2. Prepare batch explicitly
    llama_batch batch = llama_batch_init(std::max(n_prompt, 1), 0, 1);
    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i] = 1;
        batch.logits[i] = (i == n_prompt - 1);
    }
    batch.n_tokens = n_prompt;
    
    // 3. Init Sampler
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    // Use simple greedy sampling for stability
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    
    // 4. Decode loop
    std::string response = "";
    int n_predict = 256; // max new tokens (reduced for mobile)
    
    LOGI("Starting prompt eval with %d tokens...", n_prompt);
    auto t_start = std::chrono::high_resolution_clock::now();
    
    int decode_status = llama_decode(state->ctx, batch);
    
    auto t_end = std::chrono::high_resolution_clock::now();
    double elapsed_ms = std::chrono::duration<double, std::milli>(t_end - t_start).count();
    LOGI("Prompt eval finished in %.1f ms (status=%d)", elapsed_ms, decode_status);
    
    if (decode_status) {
        LOGE("failed to decode prompt (status=%d)", decode_status);
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("");
    }
    
    int n_pos = n_prompt;
    for (int i = 0; i < n_predict; i++) {
        llama_token new_token_id = llama_sampler_sample(smpl, state->ctx, -1);
        llama_sampler_accept(smpl, new_token_id);
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break; // End of generation
        }
        
        char buf[128];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            // Debug log to ensure it's not actually frozen
            if (i % 5 == 0) {
                LOGI("Decoding token %d...", i);
            }
            if (piece.find("<end_of_turn>") != std::string::npos || 
                piece.find("<|eot_id|>") != std::string::npos ||
                piece.find("<|im_end|>") != std::string::npos ||
                piece.find("<|endoftext|>") != std::string::npos) {
                break;
            }
            response.append(piece);
            if (jcallback && onTokenMethod) {
                jstring jstr = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(jcallback, onTokenMethod, jstr);
                env->DeleteLocalRef(jstr);
            }
        }
        
        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = n_pos;
        batch.seq_id[0][0] = 0;
        batch.n_seq_id[0] = 1;
        batch.logits[0] = true;
        
        if (llama_decode(state->ctx, batch)) {
            LOGE("failed to decode generation step");
            break;
        }
        n_pos++;
    }
    
    LOGI("Generation loop complete. Generated %d tokens.", n_pos - n_prompt);
    
    llama_batch_free(batch);    
    llama_sampler_free(smpl);
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
