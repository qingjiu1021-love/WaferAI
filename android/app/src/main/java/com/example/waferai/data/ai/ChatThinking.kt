package com.example.waferai.data.ai

enum class ChatThinking(val label: String) {
    DEFAULT("默认"), OFF("关闭"), LOW("低"), MEDIUM("中"), HIGH("高"), MAX("最高")
}

internal enum class ThinkingApi {
    DEEPSEEK, OPENAI, ANTHROPIC, ROUTER
}

private val deepSeekV4 = Regex("deepseek-(?:v4(?:[.-].*)?|flash(?:-.*)?)")
private val openAiReasoning = Regex("(?:gpt-[56](?:[.-].*)?|o[134](?:-.*)?)")
private val claudeEffort = Regex("claude-(?:opus-(?:4-[5-9]|5)(?:-.*)?|sonnet-(?:4-6|5)(?:-.*)?|haiku-5(?:-.*)?|fable-5(?:-.*)?|mythos-5(?:-.*)?)")
private val geminiEffort = Regex("gemini-(?:2\\.5|3(?:\\.[0-9]+)?|4)(?:-.*)")

internal fun thinkingApi(provider: AiProvider, protocol: AiProtocol, model: String): ThinkingApi? {
    val id = model.lowercase().substringAfterLast('/')
    return when {
        provider == AiProvider.OPENROUTER && protocol == AiProtocol.OPENAI &&
            (deepSeekV4.matches(id) || openAiReasoning.matches(id) || claudeEffort.matches(id) || geminiEffort.matches(id)) ->
            ThinkingApi.ROUTER
        deepSeekV4.matches(id) -> ThinkingApi.DEEPSEEK
        protocol == AiProtocol.ANTHROPIC && claudeEffort.matches(id) -> ThinkingApi.ANTHROPIC
        protocol == AiProtocol.OPENAI && provider == AiProvider.GEMINI && geminiEffort.matches(id) &&
            !listOf("image", "tts", "live", "embedding").any { it in id } -> ThinkingApi.OPENAI
        protocol != AiProtocol.ANTHROPIC && openAiReasoning.matches(id) &&
            !listOf("-chat", "-pro", "-codex", "-audio", "-image").any { it in id } &&
            id != "o1-mini" && id != "o1-preview" -> ThinkingApi.OPENAI
        else -> null
    }
}

internal fun ThinkingApi?.options(): List<ChatThinking> = when (this) {
    ThinkingApi.DEEPSEEK -> listOf(ChatThinking.DEFAULT, ChatThinking.OFF, ChatThinking.LOW, ChatThinking.HIGH, ChatThinking.MAX)
    ThinkingApi.OPENAI, ThinkingApi.ANTHROPIC, ThinkingApi.ROUTER ->
        listOf(ChatThinking.DEFAULT, ChatThinking.LOW, ChatThinking.MEDIUM, ChatThinking.HIGH)
    null -> emptyList()
}

internal fun AiProfile.thinkingOptions(): List<ChatThinking> = thinkingApi(provider, protocol, model).options()
