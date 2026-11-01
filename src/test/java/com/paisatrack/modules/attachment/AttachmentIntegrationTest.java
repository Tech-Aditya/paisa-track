package com.paisatrack.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.paisatrack.support.FinanceIntegrationTest;

class AttachmentIntegrationTest extends FinanceIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] PDF = "%PDF-1.4\n%fake receipt\n".getBytes(StandardCharsets.US_ASCII);

    @Value("${paisatrack.attachments.dir}")
    String dir;

    private ResultActions attach(Session s, UUID tx, String name, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/transactions/" + tx + "/attachments")
                .file(new MockMultipartFile("file", name, "image/png", bytes))
                .header("Authorization", "Bearer " + s.accessToken()));
    }

    private UUID anExpense(Session s) throws Exception {
        return expense(s, "450", category(s, "FOOD_LUNCH"));
    }

    @Test
    void attachListOpenAndRemoveAReceipt() throws Exception {
        Session s = register(uniqueEmail());
        UUID tx = anExpense(s);
        String json = attach(s, tx, "../../lunch bill.png", PNG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileName").value("lunch bill.png"))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.sizeBytes").value(PNG.length))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.data.id"));

        mockMvc.perform(getAuthed("/api/v1/transactions/" + tx + "/attachments", s.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(getAuthed("/api/v1/transactions/" + tx, s.accessToken()))
                .andExpect(jsonPath("$.data.attachmentCount").value(1));
        mockMvc.perform(getAuthed("/api/v1/attachments/" + id + "/content", s.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        Path stored = Path.of(dir).toAbsolutePath().resolve(s.userId() + "/" + id);
        assertThat(stored).exists();
        mockMvc.perform(authed(delete("/api/v1/attachments/" + id), s.accessToken())).andExpect(status().isNoContent());
        mockMvc.perform(getAuthed("/api/v1/attachments/" + id + "/content", s.accessToken())).andExpect(status().isNotFound());
        assertThat(stored).doesNotExist();
    }

    @Test
    void typeIsCheckedFromTheContentNotTheName() throws Exception {
        Session s = register(uniqueEmail());
        UUID tx = anExpense(s);
        attach(s, tx, "receipt.png", "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnsupportedMediaType());
        attach(s, tx, "receipt.exe", PDF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileName").value("receipt.pdf"))
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"));
        attach(s, tx, "empty.png", new byte[0]).andExpect(status().isBadRequest());
    }

    @Test
    void sizeAndCountAreLimited() throws Exception {
        Session s = register(uniqueEmail());
        UUID tx = anExpense(s);
        byte[] big = Arrays.copyOf(PNG, 5 * 1024 * 1024 + 1);
        attach(s, tx, "big.png", big).andExpect(status().isBadRequest());
        for (int i = 0; i < 5; i++) {
            attach(s, tx, "r" + i + ".png", PNG).andExpect(status().isCreated());
        }
        attach(s, tx, "sixth.png", PNG).andExpect(status().isUnprocessableContent());
    }

    @Test
    void receiptsAreOnlyVisibleToTheirOwner() throws Exception {
        Session owner = register(uniqueEmail());
        Session other = register(uniqueEmail());
        UUID tx = anExpense(owner);
        String json = attach(owner, tx, "r.png", PNG).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.data.id");

        attach(other, tx, "mine.png", PNG).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/transactions/" + tx + "/attachments", other.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/attachments/" + id + "/content", other.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/attachments/" + id), other.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/attachments/" + id + "/content", owner.accessToken())).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/attachments/" + id + "/content", "")).andExpect(status().isUnauthorized());
    }

    @Test
    void storageRefusesKeysOutsideItsFolder() throws Exception {
        LocalAttachmentStorage storage = new LocalAttachmentStorage(Files.createTempDirectory("pt-att").toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.put("../escape", PNG)).isInstanceOf(RuntimeException.class);
    }
}
