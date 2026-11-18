#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <sstream>
#include <iomanip>
#include <limits>
#include "json.hpp"
#include "whisper_domain.h"

namespace {
std::atomic<bool> cancelled{false}; std::mutex running;
void quiet(ggml_log_level, const char *, void *) {}
std::string string(JNIEnv *env, jstring value) { const char *raw = env->GetStringUTFChars(value, nullptr); std::string result(raw); env->ReleaseStringUTFChars(value, raw); return result; }
uint32_t u32(const unsigned char *p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }
uint16_t u16(const unsigned char *p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }
std::vector<float> wav(const std::string &path) {
    std::ifstream in(path, std::ios::binary); char riff[12]; in.read(riff, 12);
    if (!in || std::string(riff,4)!="RIFF" || std::string(riff+8,4)!="WAVE") throw std::runtime_error("wav_invalid");
    bool format_ok = false;
    while (in) {
        char id[4]; unsigned char length[4]; in.read(id,4); in.read((char*)length,4); if (!in) break;
        uint32_t size=u32(length); if (size > 1024U*1024U*1024U) throw std::runtime_error("wav_too_large");
        if (std::string(id,4)=="fmt ") {
            std::vector<unsigned char> format(size); in.read((char*)format.data(),size);
            format_ok=size>=16 && u16(format.data())==1 && u16(format.data()+2)==1 && u32(format.data()+4)==16000 && u16(format.data()+14)==16;
        } else if (std::string(id,4)=="data") {
            if (!format_ok) throw std::runtime_error("wav_format");
            std::vector<float> samples(size/2); unsigned char pair[2];
            for (auto &sample:samples) { in.read((char*)pair,2); if (!in) throw std::runtime_error("wav_truncated"); sample=(int16_t)u16(pair)/32768.0f; }
            return samples;
        } else in.seekg(size,std::ios::cur);
        if (size&1) in.seekg(1,std::ios::cur);
    }
    throw std::runtime_error("wav_empty");
}
std::string escaped(const char *source) { std::string out="\""; for (const unsigned char c:std::string(source)) { switch(c) { case '\"':out+="\\\"";break;case '\\':out+="\\\\";break;case '\n':out+="\\n";break;case '\r':out+="\\r";break;case '\t':out+="\\t";break;default:if(c<32){const char *hex="0123456789abcdef";out+="\\u00";out+=hex[c>>4];out+=hex[c&15];}else out+=char(c); } } return out+"\""; }
jstring java_string(JNIEnv *env,const std::string &value){auto bytes=env->NewByteArray(value.size());env->SetByteArrayRegion(bytes,0,value.size(),reinterpret_cast<const jbyte*>(value.data()));auto cls=env->FindClass("java/lang/String");auto constructor=env->GetMethodID(cls,"<init>","([BLjava/lang/String;)V");auto enc=env->NewStringUTF("UTF-8");auto result=(jstring)env->NewObject(cls,constructor,bytes,enc);env->DeleteLocalRef(bytes);env->DeleteLocalRef(cls);env->DeleteLocalRef(enc);return result;}
whisper_alignment_heads_preset alignment_preset(const std::string & path) {
    unsigned char header[40];
    std::ifstream model(path, std::ios::binary);
    model.read((char *)header, sizeof(header));
    if (!model) throw std::runtime_error("model_header_invalid");
    const auto text_layers = u32(header + 36);
    const auto audio_layers = u32(header + 20);
    if (text_layers >= 32) return WHISPER_AHEADS_LARGE_V3;
    if (text_layers == 24) return WHISPER_AHEADS_MEDIUM;
    if (text_layers == 12) return WHISPER_AHEADS_SMALL;
    if (text_layers == 6) return WHISPER_AHEADS_BASE;
    return audio_layers >= 32 ? WHISPER_AHEADS_LARGE_V3_TURBO : WHISPER_AHEADS_TINY;
}
}
extern "C" JNIEXPORT void JNICALL Java_dev_murmur_android_intelligence_NativeWhisper_cancel(JNIEnv *,jobject){cancelled.store(true);}
extern "C" JNIEXPORT jstring JNICALL Java_dev_murmur_android_intelligence_NativeWhisper_transcribe(JNIEnv *env,jobject,jstring model_path,jstring input_path,jstring language){
    std::lock_guard<std::mutex> lock(running);cancelled.store(false);
    try {
        whisper_log_set(quiet,nullptr);
        auto cp=whisper_context_default_params();cp.use_gpu=false;
        const auto path=string(env,model_path);std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(path.c_str(),cp),whisper_free);
        if(!ctx)throw std::runtime_error("model_load_failed");
        auto samples=wav(string(env,input_path));const auto lang=string(env,language);
        auto params=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);params.n_threads=2;params.translate=false;params.no_timestamps=false;
        params.language=lang=="auto"?nullptr:lang.c_str();params.print_progress=false;params.print_realtime=false;params.print_timestamps=false;params.print_special=false;
        params.abort_callback=[](void*){return cancelled.load();};
        if(whisper_full(ctx.get(),params,samples.data(),samples.size())!=0)throw std::runtime_error("transcribe_failed");
        std::ostringstream output;output<<"["<<std::setprecision(12);
        const int n=whisper_full_n_segments(ctx.get());
        for(int i=0;i<n;i++){if(i)output<<",";output<<"{\"start\":"<<whisper_full_get_segment_t0(ctx.get(),i)/100.0<<",\"end\":"<<whisper_full_get_segment_t1(ctx.get(),i)/100.0<<",\"text\":"<<escaped(whisper_full_get_segment_text(ctx.get(),i))<<"}";}
        output<<"]";return java_string(env,output.str());
    }catch(const std::exception&){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"local_model_failed");return nullptr;}
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_murmur_android_intelligence_NativeWhisper_transcribeSpans(JNIEnv *env,jobject,jstring model_path,jstring input_path,jstring language,jstring encoded_spans){
    std::lock_guard<std::mutex> lock(running);cancelled.store(false);
    try {
        whisper_log_set(quiet,nullptr);
        const auto path=string(env,model_path);const auto lang=string(env,language);
        auto cp=whisper_context_default_params();cp.use_gpu=false;
        cp.flash_attn=false;cp.dtw_token_timestamps=true;cp.dtw_aheads_preset=alignment_preset(path);
        std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(path.c_str(),cp),whisper_free);
        if(!ctx)throw std::runtime_error("model_load_failed");
        const auto samples=wav(string(env,input_path));const auto spans=nlohmann::json::parse(string(env,encoded_spans));
        if(!spans.is_array())throw std::runtime_error("invalid_spans");
        std::vector<murmur_whisper::Span> ranges;
        for(const auto& span:spans){
            const double start=span.at("start").get<double>();const double end=span.at("end").get<double>();
            if(start<0 || end<=start || end>samples.size()/16000.0+1)throw std::runtime_error("invalid_span");
            ranges.push_back({start,end});
        }
        std::vector<std::vector<std::string>> collected(spans.size());
        for(const auto& placed:murmur_whisper::chunks(ranges)){
            if(cancelled.load())throw std::runtime_error("cancelled");
            std::vector<float> buffer;
            double speech=0;
            for(const auto& part:placed){
                const auto& span=ranges[part.span];
                const size_t from=std::min(samples.size(),size_t(span.start*16000));
                const size_t to=std::min(samples.size(),size_t(span.end*16000));
                buffer.resize(size_t(part.offset*16000),0.0f);
                buffer.insert(buffer.end(),samples.begin()+from,samples.begin()+to);
                speech+=part.length;
            }
            if(buffer.size()<24000)buffer.resize(24000,0.0f);
            auto params=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            params.n_threads=2;params.translate=false;params.token_timestamps=true;params.no_context=true;params.suppress_nst=true;
            params.language=lang=="auto"?nullptr:lang.c_str();params.print_progress=false;params.print_realtime=false;params.print_timestamps=false;params.print_special=false;
            params.abort_callback=[](void*){return cancelled.load();};
            if(whisper_full(ctx.get(),params,buffer.data(),buffer.size())!=0)throw std::runtime_error("transcribe_failed");
            std::vector<std::pair<double,std::string>> words;
            double previous=-std::numeric_limits<double>::infinity();
            for(int segment=0;segment<whisper_full_n_segments(ctx.get());++segment){
                for(int index=0;index<whisper_full_n_tokens(ctx.get(),segment);++index){
                    const auto data=whisper_full_get_token_data(ctx.get(),segment,index);
                    if(data.id>=whisper_token_eot(ctx.get()))continue;
                    const char* raw=whisper_token_to_str(ctx.get(),data.id);
                    const std::string bytes=raw?raw:"";
                    const double at=(data.t_dtw>=0?data.t_dtw:data.t0)/100.0;
                    bool next_utterance=false;
                    for(const auto& part:placed)if(previous<part.offset-0.1 && at>=part.offset-0.1){next_utterance=true;break;}
                    previous=at;
                    if(!words.empty() && (bytes.empty() || bytes[0]!=' ') && !next_utterance)words.back().second+=bytes;
                    else words.emplace_back(at,bytes);
                }
            }
            words.erase(std::remove_if(words.begin(),words.end(),[](auto& word){word.second=murmur_whisper::trim(word.second);return word.second.empty();}),words.end());
            std::vector<std::string> text_words;
            for(const auto& word:words)text_words.push_back(word.second);
            if(words.empty() || words.size()>6.0*speech+8.0 || murmur_whisper::repeats(text_words))continue;
            for(const auto& word:words){
                size_t target=0;
                for(size_t index=0;index<placed.size();++index)if(placed[index].offset<=word.first+0.1)target=index;
                collected[placed[target].span].push_back(word.second);
            }
        }
        nlohmann::json result=nlohmann::json::array();
        for(size_t index=0;index<spans.size();++index){
            std::string text;
            for(const auto& word:collected[index]){if(!text.empty())text+=' ';text+=word;}
            if(text.empty() || murmur_whisper::hallucination(text))continue;
            result.push_back({{"id",spans[index].at("id")},{"start",ranges[index].start},{"end",ranges[index].end},{"text",text}});
        }
        return java_string(env,result.dump());
    }catch(const std::exception&){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"local_model_failed");return nullptr;}
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_murmur_android_intelligence_NativeWhisper_detectLanguage(JNIEnv *env,jobject,jstring model_path,jstring input_path,jstring encoded_windows){
    std::lock_guard<std::mutex> lock(running);cancelled.store(false);
    try {
        whisper_log_set(quiet,nullptr);auto cp=whisper_context_default_params();cp.use_gpu=false;
        const auto path=string(env,model_path);std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(path.c_str(),cp),whisper_free);
        if(!ctx)throw std::runtime_error("model_load_failed");
        const auto samples=wav(string(env,input_path));const auto windows=nlohmann::json::parse(string(env,encoded_windows));
        if(!windows.is_array())throw std::runtime_error("invalid_windows");
        nlohmann::json result=nlohmann::json::array();int window_index=0;
        for(const auto& window:windows){
            if(cancelled.load())throw std::runtime_error("cancelled");
            std::vector<float> buffer;
            for(const auto& span:window){
                const double start=span.at("start").get<double>();const double end=span.at("end").get<double>();
                if(start<0 || end<start)throw std::runtime_error("invalid_span");
                const size_t from=std::min(samples.size(),size_t(start*16000));const size_t to=std::min(samples.size(),size_t(end*16000));
                if(!buffer.empty())buffer.resize(buffer.size()+8000,0.0f);
                buffer.insert(buffer.end(),samples.begin()+from,samples.begin()+to);
            }
            if(buffer.size()>30*16000)buffer.resize(30*16000);
            if(buffer.size()<16000){window_index++;continue;}
            if(whisper_pcm_to_mel(ctx.get(),buffer.data(),buffer.size(),2)!=0)throw std::runtime_error("mel_failed");
            std::vector<float> probabilities(whisper_lang_max_id()+1);
            int top=whisper_lang_auto_detect(ctx.get(),0,2,probabilities.data());
            if(top<0)throw std::runtime_error("language_failed");
            auto p=[&](const char* lang){int id=whisper_lang_id(lang);return id<0?0.0f:probabilities.at(id);};
            result.push_back({{"window",window_index++},{"top",whisper_lang_str(top)},{"ca",p("ca")},{"es",p("es")},{"en",p("en")}});
        }
        return java_string(env,result.dump());
    }catch(const std::exception&){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"local_model_failed");return nullptr;}
}
