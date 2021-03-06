package com.github.kjarosh.agh.pp.rest.dto;

import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.core.JsonProcessingException;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Kamil Jarosz
 */
class BulkVertexCreationRequestDtoTest {
    @Test
    void serializeDeserialize() throws JsonProcessingException {
        List<VertexCreationRequestDto> vertices = Arrays.asList(
                VertexCreationRequestDto.fromString("SPACE/a"),
                VertexCreationRequestDto.fromString("USER/b"));
        BulkVertexCreationRequestDto dto = new BulkVertexCreationRequestDto(vertices);

        ObjectMapper mapper = new ObjectMapper();
        String s = mapper.writeValueAsString(dto);

        assertThat(s).isEqualTo("{\"vertices\":[\"SPACE/a\",\"USER/b\"]}");

        BulkVertexCreationRequestDto dto2 = mapper.readValue(s, BulkVertexCreationRequestDto.class);

        assertThat(dto2).isEqualTo(dto);
    }
}
