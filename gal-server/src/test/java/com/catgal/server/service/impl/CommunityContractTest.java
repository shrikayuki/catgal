package com.catgal.server.service.impl;

import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.dto.ReviewAddDTO;
import com.catgal.common.domain.dto.ResourceAddDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommunityContractTest {
    @Test
    void resourceRequiredFieldsAndLengthsMatchExistingSchema() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var dto = new ResourceAddDTO();
            dto.setGameId(1L);
            dto.setName("资源");
            dto.setDownloadUrl("https://example.com/file");
            dto.setFileSize("4GB");
            dto.setTypes("pc"); dto.setLanguages("中文"); dto.setPlatforms("win");
            assertTrue(validator.validate(dto).isEmpty());
            dto.setName(" ");
            assertFalse(validator.validate(dto).isEmpty());
            dto.setName("字".repeat(201));
            assertFalse(validator.validate(dto).isEmpty());
            dto.setName("资源"); dto.setPassword("字".repeat(101));
            assertFalse(validator.validate(dto).isEmpty());
        }
    }
    @Test
    void acceptsOrdinaryCommentTextAndRejectsBlankOrTooLongText() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var dto = new CommentAddDTO();
            dto.setGameId(1L);
            dto.setContent("这个游戏真不错！");
            assertTrue(validator.validate(dto).isEmpty());
            dto.setContent(" ");
            assertFalse(validator.validate(dto).isEmpty());
            dto.setContent("字".repeat(501));
            assertFalse(validator.validate(dto).isEmpty());
        }
    }

    @Test
    void validatesReplyIdentityAndText() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var dto = new ReplyCommentDTO();
            dto.setGameId(1L); dto.setParentId(2L); dto.setContent("回复");
            assertTrue(validator.validate(dto).isEmpty());
            dto.setParentId(0L);
            assertFalse(validator.validate(dto).isEmpty());
        }
    }

    @Test
    void enforcesReviewEnumRanges() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var dto = new ReviewAddDTO();
            dto.setGameId(1L); dto.setRecommendLevel(4); dto.setScore(8); dto.setPlayStatus(5); dto.setSpoilerLevel(1);
            assertTrue(validator.validate(dto).isEmpty());
            dto.setPlayStatus(7);
            assertFalse(validator.validate(dto).isEmpty());
        }
    }

    @Test
    void folderLengthsMatchExistingSchema() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var dto = new CreateFolderDTO();
            dto.setName("名".repeat(50)); dto.setDescription("述".repeat(200));
            assertTrue(validator.validate(dto).isEmpty());
            dto.setName("名".repeat(51));
            assertFalse(validator.validate(dto).isEmpty());
        }
    }
}
