#include <jni.h>
#include <llama.h>
#include "chat.h"
#include "log.h"
#include "json-schema-to-grammar.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::atomic<bool> cancelled{false};
std::mutex running;
void quiet(ggml_log_level, const char *, void *) {}
std::string string(JNIEnv *env, jstring value) {
    if (!value) return {};
    jclass cls = env->FindClass("java/lang/String");
    jmethodID method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    auto encoding = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray)env->CallObjectMethod(value, method, encoding);
    const auto size = env->GetArrayLength(bytes);
    std::string result(size, '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
jstring java_string(JNIEnv *env, const std::string &value) {
    auto bytes = env->NewByteArray(value.size());
    env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    auto cls = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    auto result = (jstring)env->NewObject(cls, constructor, bytes, encoding);
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
}

extern "C" JNIEXPORT void JNICALL Java_dev_murmur_android_intelligence_NativeLlm_cancel(JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT jstring JNICALL Java_dev_murmur_android_intelligence_NativeLlm_generate(
    JNIEnv *env, jobject, jstring model_file, jstring system_text, jstring document_text, jstring question_text, jint maximum, jstring schema_text) {
    std::lock_guard<std::mutex> lock(running);
    cancelled.store(false);
    try {
        llama_log_set(quiet, nullptr);
        common_log_set_verbosity_thold(-1);
        llama_backend_init();
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        const auto path = string(env, model_file);
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), params), llama_model_free);
        if (!model) throw std::runtime_error("model_load_failed");
        auto cparams = llama_context_default_params();
        cparams.n_ctx = 4096; cparams.n_batch = 256; cparams.n_ubatch = 128;
        cparams.n_threads = 2; cparams.n_threads_batch = 2;
        cparams.abort_callback = [](void *) { return cancelled.load(); };
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cparams), llama_free);
        if (!context) throw std::runtime_error("context_load_failed");
        const auto system = string(env, system_text);
        const auto document = string(env, document_text);
        const auto question = string(env, question_text);
        const auto schema = string(env, schema_text);
        const auto input = document + "\n\n---\n" + question;
        // The C API only recognises a fixed list of older templates. Gemma4 and the compact
        // fallback models require their GGUF Jinja template and an explicit non-thinking turn.
        auto templates = common_chat_templates_init(model.get(), "");
        common_chat_templates_inputs chat;
        chat.use_jinja = true;
        chat.enable_thinking = false;
        chat.force_pure_content = true;
        chat.add_generation_prompt = true;
        if (!system.empty()) {
            common_chat_msg message;
            message.role = "system";
            message.content = system;
            chat.messages.push_back(message);
        }
        common_chat_msg message;
        message.role = "user";
        message.content = input;
        chat.messages.push_back(message);
        const auto formatted = common_chat_templates_apply(templates.get(), chat);
        const std::string prompt = formatted.prompt.str();
        const auto vocab = llama_model_get_vocab(model.get());
        std::vector<llama_token> tokens(prompt.size() + 32);
        int count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
        if (count < 0) { tokens.resize(-count); count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true); }
        const int remaining = (int)llama_n_ctx(context.get()) - count - 8;
        if (count <= 0 || remaining < 128) throw std::runtime_error("context_overflow");
        const int output_limit = std::min<int>(std::min<int>(maximum, 2048), remaining);
        for (int offset = 0; offset < count; offset += 256) {
            if (cancelled.load()) throw std::runtime_error("cancelled");
            const int n = std::min(256, count - offset);
            auto batch = llama_batch_get_one(tokens.data() + offset, n);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("decode_failed");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!schema.empty() && schema != "null") {
            const auto grammar = json_schema_to_grammar(common_json::parse(schema));
            auto constrained = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
            if (!constrained) throw std::runtime_error("grammar_failed");
            llama_sampler_chain_add(sampler.get(), constrained);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string answer;
        for (int i = 0; i < output_limit; i++) {
            if (cancelled.load()) throw std::runtime_error("cancelled");
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (size > 0) answer.append(piece.data(), size);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("decode_failed");
        }
        return java_string(env, answer);
    } catch (const std::exception &) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "local_model_failed");
        return nullptr;
    }
}
