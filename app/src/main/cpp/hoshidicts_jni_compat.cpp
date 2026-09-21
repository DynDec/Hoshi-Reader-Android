// Keep the app's JNI ABI in the parent repository while the bridge submodule
// remains on its latest published revision. The included bridge owns all of
// the model conversion code; only the two newer entry points are adapted here.
#define Java_de_manhhao_hoshi_HoshiDicts_importDictionary \
    Java_de_manhhao_hoshi_HoshiDicts_legacyImportDictionary
#define Java_de_manhhao_hoshi_HoshiDicts_lookup \
    Java_de_manhhao_hoshi_HoshiDicts_legacyLookup
#include "../../../../third_party/hoshidicts-kotlin-bridge/app/src/main/cpp/hoshidicts_jni.cpp"
#undef Java_de_manhhao_hoshi_HoshiDicts_importDictionary
#undef Java_de_manhhao_hoshi_HoshiDicts_lookup

namespace {
    jobject new_import_result_with_error(JNIEnv *env, const ImportResult &result) {
        const auto &counts = result.summary.counts;
        jclass cls = env->FindClass("de/manhhao/hoshi/ImportResult");
        jmethodID ctor = env->GetMethodID(
                cls, "<init>", "(ZLjava/lang/String;JJJJJJLjava/lang/String;)V");
        jstring title = new_string(env, result.title);
        jstring error = new_string(env, result.error);
        jobject out = env->NewObject(
                cls,
                ctor,
                static_cast<jboolean>(result.success),
                title,
                static_cast<jlong>(counts.terms.total),
                summary_meta_count(counts.termMeta, "total"),
                summary_meta_count(counts.termMeta, "freq"),
                summary_meta_count(counts.termMeta, "pitch") +
                        summary_meta_count(counts.termMeta, "ipa"),
                static_cast<jlong>(counts.kanji.total),
                static_cast<jlong>(counts.media.total),
                error);
        env->DeleteLocalRef(title);
        env->DeleteLocalRef(error);
        return out;
    }

    LookupFrequencyOrder frequency_order_from_int(jint value) {
        switch (value) {
            case 1:
                return LookupFrequencyOrder::Ascending;
            case 2:
                return LookupFrequencyOrder::Descending;
            case 3:
                return LookupFrequencyOrder::Disabled;
            default:
                return LookupFrequencyOrder::Auto;
        }
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_de_manhhao_hoshi_HoshiDicts_importDictionary(
        JNIEnv *env, jobject, jstring zip_path, jstring output_dir, jboolean low_ram) {
    const auto zip_path_str = jstring_to_std_string(env, zip_path);
    const auto output_dir_str = jstring_to_std_string(env, output_dir);
    const auto result = dictionary_importer::import(zip_path_str, output_dir_str, low_ram);
    return new_import_result_with_error(env, result);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_manhhao_hoshi_HoshiDicts_lookupWithOptions(
        JNIEnv *env,
        jobject,
        jlong session,
        jstring text,
        jint max_results,
        jint scan_length,
        jint frequency_order,
        jstring frequency_dictionary) {
    LookupObject *obj = as_object(session);
    LookupOptions options;
    options.frequency_order = frequency_order_from_int(frequency_order);
    if (frequency_dictionary != nullptr) {
        options.frequency_dictionary = jstring_to_std_string(env, frequency_dictionary);
    }
    const auto text_str = jstring_to_std_string(env, text);
    const auto result = obj->lookup->lookup(
            text_str,
            static_cast<int>(max_results),
            static_cast<size_t>(scan_length),
            options);
    return new_lookup_result_array(env, result);
}
