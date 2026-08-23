package com.misc.code.smartnote.tools;

import com.misc.code.smartnote.service.DocumentService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@Slf4j
@AllArgsConstructor
public class NoteTools {

    private final DocumentService documentService;
    private final JdbcTemplate jdbcTemplate;

    @Tool(description = "保存一篇新笔记到知识库。用于用户要求记录，保存，整理内容为笔记时。")
    public String saveNote(
            @ToolParam(description = "笔记标题，简洁的中文名词短语，不含特殊字符")String title,
            @ToolParam(description = "笔记正文，Markdown格式，用 ## 组织小节")String content
    ) throws IOException {
        String fileName = title + ".md";
        documentService.ingest(fileName, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        log.info("工具调用: saveNote, 文件={}, 长度={}", fileName, content.length());
        return "笔记《"+title+"》已保存并完成向量化";
    }

    @Tool(description = "列出知识库中现有的所有笔记文件名。用于用户询问有哪些笔记、知识库里有什么时。")
    public List<String> listNotes(){
        return jdbcTemplate.queryForList(
                "select distinct metadata->>'filename' from vector_store", String.class);
    }
}
