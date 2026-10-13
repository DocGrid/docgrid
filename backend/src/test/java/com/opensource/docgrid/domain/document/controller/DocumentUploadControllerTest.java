package com.opensource.docgrid.domain.document.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

@WebMvcTest(DocumentUploadController.class)
@DisplayName("DocumentUploadController 테스트")
class DocumentUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentUploadFacade documentUploadFacade;

    @MockitoBean
    private DocumentVersionUploadFacade documentVersionUploadFacade;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("인증된 사용자가 TXT 파일을 업로드하면 201 응답을 반환한다")
    void upload_returnsCreated_when_requestIsValid() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "sample.txt", "text/plain", "hello".getBytes()
        );
        DocumentUploadResponse response = new DocumentUploadResponse(
            1L, 2L, 3L, 4L, DocumentStatus.UPLOADED, EmbeddingJobStatus.PENDING
        );
        given(documentUploadFacade.upload(org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.any(DocumentUploadRequest.class),
            org.mockito.ArgumentMatchers.isNull())).willReturn(response);

        mockMvc.perform(multipart("/api/documents")
                .file(file)
                .param("title", "테스트 문서")
                .param("description", "설명")
                .param("visibility", "PRIVATE")
                .with(csrf())
                .with(authentication(authenticationWithUserId(10L))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.documentId").value(1))
            .andExpect(jsonPath("$.data.documentVersionId").value(2))
            .andExpect(jsonPath("$.data.fileObjectId").value(3))
            .andExpect(jsonPath("$.data.embeddingJobId").value(4))
            .andExpect(jsonPath("$.data.documentStatus").value("UPLOADED"))
            .andExpect(jsonPath("$.data.jobStatus").value("PENDING"));
    }

    @Test
    @DisplayName("인증되지 않은 사용자의 업로드 요청은 401을 반환한다")
    void upload_returnsUnauthorized_when_userIsNotAuthenticated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "sample.txt", "text/plain", "hello".getBytes()
        );

        mockMvc.perform(multipart("/api/documents")
                .file(file)
                .param("title", "테스트 문서")
                .param("visibility", "PRIVATE")
                .with(csrf()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증된 사용자가 새 문서 버전을 업로드하면 201 응답을 반환한다")
    void uploadVersion_returnsCreated_when_requestIsValid() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "sample.txt", "text/plain", "changed".getBytes()
        );
        DocumentVersionUploadResponse response = new DocumentVersionUploadResponse(
            1L, 5L, 2, 6L, 2L, DocumentStatus.INDEXED,
            DocumentVersionStatus.UPLOADED, EmbeddingJobStatus.PENDING
        );
        given(documentVersionUploadFacade.upload(
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any(DocumentVersionUploadRequest.class),
            org.mockito.ArgumentMatchers.eq("version-attempt-1")
        )).willReturn(response);

        mockMvc.perform(multipart("/api/documents/{documentId}/versions", 1L)
                .file(file)
                .header("Idempotency-Key", "version-attempt-1")
                .with(csrf())
                .with(authentication(authenticationWithUserId(10L))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.documentId").value(1))
            .andExpect(jsonPath("$.data.documentVersionId").value(5))
            .andExpect(jsonPath("$.data.versionNo").value(2))
            .andExpect(jsonPath("$.data.embeddingJobId").value(6))
            .andExpect(jsonPath("$.data.currentVersionId").value(2))
            .andExpect(jsonPath("$.data.documentStatus").value("INDEXED"))
            .andExpect(jsonPath("$.data.versionStatus").value("UPLOADED"))
            .andExpect(jsonPath("$.data.jobStatus").value("PENDING"));
    }

    private UsernamePasswordAuthenticationToken authenticationWithUserId(Long userId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user", "password", List.of()
        );
        authentication.setDetails(userId);
        return authentication;
    }
}
