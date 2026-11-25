package com.catgal.server.search;

import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.CommentMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameListAssemblerTest {
    @Test
    @SuppressWarnings("unchecked")
    void batchesCountersAndUsesPersistedRatingAndListFields() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        CommentMapper comments = mock(CommentMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyCollection())).thenReturn(List.of("3", "4", "5", "7", "8", "9"));
        when(comments.countVisibleInGames(List.of(1L, 2L)))
                .thenReturn(List.of(new CommentMapper.GameCommentCount(1L, 2)));
        GameListAssembler assembler = new GameListAssembler(redis, comments,
                mock(com.catgal.server.catalog.GameRelationsService.class));
        var result = assembler.assemble(List.of(new Game().setId(1L).setName("夏").setRating(new BigDecimal("8.5")),
                new Game().setId(2L)));
        assertEquals(2, result.size());
        assertEquals(2, result.get(0).getCommentCount());
        assertEquals(0, result.get(1).getCommentCount());
        assertEquals(3, result.get(0).getDownloadCount());
        assertEquals(8, result.get(1).getFavoriteCount());
        assertEquals(9, result.get(1).getViewCount());
        assertEquals(new BigDecimal("8.5"), result.get(0).getRating());
        assertEquals(BigDecimal.ZERO, result.get(1).getRating());
        assertNull(result.get(0).getIsFavorite());
        assertNull(result.get(0).getResourceCount());
        verify(values).multiGet(List.of("game:download:count:1", "favorite:count:1", "look:count:1",
                "game:download:count:2", "favorite:count:2", "look:count:2"));
        verify(comments).countVisibleInGames(List.of(1L, 2L));
    }
}
