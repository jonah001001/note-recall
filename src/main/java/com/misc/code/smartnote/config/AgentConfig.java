package com.misc.code.smartnote.config;

import com.misc.code.smartnote.advisors.RagMetricsAdvisor;
import com.misc.code.smartnote.processers.RerankPostProcessor;
import com.misc.code.smartnote.tools.NoteTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AgentConfig {

    private static final String SYSTEM_PROMPT = "你是一个用户笔记阅读助手，" +
            "需要根据用户的笔记准确的找出跟用户问题相关的内容，" +
            "遇到笔记上没有的内容，请诚实的说笔记上没有此问题的相关内容，" +
            "不允许从外部或你自身的知识来解答。请以清晰的自然语言回答用户。";

    private static final String QA_PROMPT_TEMPLATE = """
            {query}
            
            以下是从笔记中检索到的相关内容：
            ---------------------------
            {context}
            ---------------------------
            请仅依据以上笔记内容回答；若内容不足以回答时，请明确说明笔记中没有相关的记录
            """;

    private static final String COMPRESSION_TEMPLATE = """
            请根据对话历史,把用户的后续问题改写成一个独立、完整、无需上下文即可理解的问题。
    
            规则:
            1. 保持问题的原始语言,不要翻译;
            2. 如果问题本身已经独立完整,原样返回,一个字都不要改;
            3. 只输出改写后的问题本身,不要任何解释、前缀或标点之外的内容。
    
            对话历史:
            {history}
    
            后续问题: {query}
    
            改写后的问题:
            """;

    private static final String EMPTY_CONTEXT_TEMPLATE = """
            用户的问题超出了笔记的范围，请礼貌告知无法回答，并建议用户询问笔记相关的内容。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 VectorStore vectorStore,
                                 ChatMemory chatMemory,
                                 RerankPostProcessor rerankPostProcessor){
        CompressionQueryTransformer compression = CompressionQueryTransformer.builder()
                .chatClientBuilder(builder.clone())
                .promptTemplate(new PromptTemplate(COMPRESSION_TEMPLATE))
                .build();
        QueryTransformer loggingTransformer = query -> {
            Query transform = compression.transform(query);
            log.info("查询改写：[{}] -> [{}]", query.text(), transform.text());
            return transform;
        };

        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(20)
                        .similarityThreshold(0.35)
                        .build())
                .documentPostProcessors(rerankPostProcessor)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .promptTemplate(new PromptTemplate(QA_PROMPT_TEMPLATE))
                        .emptyContextPromptTemplate(new PromptTemplate(EMPTY_CONTEXT_TEMPLATE))
                        .allowEmptyContext(false)
                        .build())
                .queryTransformers(loggingTransformer)
                .order(2)
                .build();
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                            .order(1).build(),
                        ragAdvisor,
                        new RagMetricsAdvisor())
                .build();
    }


    @Bean(name = "agentClient")
    public ChatClient agentClient(ChatClient.Builder builder,
                                  ChatMemory chatMemory,
                                  NoteTools noteTools) {
        return builder.clone()
                .defaultSystem("""
                    你是用户的笔记管理助手,可以调用工具帮用户保存笔记、查看笔记列表。
                    保存笔记时,先把内容整理成结构清晰的 Markdown(用 ## 组织小节)再调用工具。
                    工具执行后,用一句话向用户确认结果。""")
                .defaultTools(noteTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).order(1).build())
                .build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
