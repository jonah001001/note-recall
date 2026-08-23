package com.misc.code.smartnote.model.dto;

import java.util.List;

public record ChatResp(String content, List<Citation> citations) {
}
