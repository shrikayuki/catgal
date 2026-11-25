package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.domain.vo.UserPrizeVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RewardTransactionServiceImplTest {
    private final IRewardControlService control = mock(IRewardControlService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserCutePointsMapper balances = mock(UserCutePointsMapper.class);
    private final CutePointsRecordMapper ledger = mock(CutePointsRecordMapper.class);
    private final PrizePoolMapper prizes = mock(PrizePoolMapper.class);
    private final DrawRecordMapper draws = mock(DrawRecordMapper.class);
    private final RewardRequestMapper requests = mock(RewardRequestMapper.class);
    private final RewardOutboxMapper outbox = mock(RewardOutboxMapper.class);
    private final IUserAccountStatusService accounts = mock(IUserAccountStatusService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2099-12-11T12:00:00Z"), IUserAccountStatusService.TIME_ZONE);
    private RewardTransactionServiceImpl service;
    private UserCutePoints balance;
    private PrizePool prize;
    private final String key = "f0000000-0000-0000-0000-000000000001";

    @BeforeEach void setup() {
        for (Class<?> type : List.of(RewardRequest.class, UserCutePoints.class, PrizePool.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RewardTransactionServiceImpl(control, users, balances, ledger, prizes, draws, requests,
                outbox, accounts, json, new TransactionTemplate(manager), clock);
        User user = new User(); user.setId(101L); user.setStatus(1);
        when(users.selectForUpdate(101L)).thenReturn(user);
        balance = new UserCutePoints().setUserId(101L).setCutePoints(10).setTotalEarned(10).setTotalSpent(0).setRevision(0L);
        when(balances.lockBalance(101L)).thenReturn(balance);
        when(balances.updateById(any())).thenReturn(1);
        when(ledger.insert(any(CutePointsRecord.class))).thenReturn(1);
        when(draws.insert(any(DrawRecord.class))).thenReturn(1);
        when(requests.insert(any(RewardRequest.class))).thenAnswer(invocation -> {
            ((RewardRequest) invocation.getArgument(0)).setId(1001L); return 1;
        });
        when(outbox.insert(any(RewardOutbox.class))).thenReturn(1);
        prize = new PrizePool().setId(501L).setPrizeName("fixture").setPrizeType(4).setMonth("209912")
                .setWeight(10).setTotalStock(1).setRemainStock(1).setStatus(1).setRevision(0L);
        when(prizes.lockMonth("209912")).thenReturn(List.of(prize));
        when(prizes.updateById(any())).thenReturn(1);
    }

    @Test void drawPersistsCostStockResultAndOutboxBeforeCommitting() {
        UserPrizeVO result = service.draw(101L, key);
        assertEquals(5, balance.getCutePoints()); assertEquals(5, balance.getTotalSpent());
        assertEquals(1L, balance.getRevision()); assertEquals(0, prize.getRemainStock());
        assertEquals("draw:101:" + key, result.getBizId()); assertEquals(5, result.getPointsBalance());
        assertFalse(result.getReplayed());
        var order = inOrder(control, users, balances, prizes, ledger, draws, requests, outbox, manager);
        order.verify(control).requireActiveTransaction(); order.verify(users).selectForUpdate(101L);
        order.verify(balances).lockBalance(101L); order.verify(prizes).lockMonth("209912");
        order.verify(prizes).updateById(any()); order.verify(balances).updateById(any());
        order.verify(ledger).insert(argThat(record -> record.getBeforePoints() == 10 && record.getAfterPoints() == 5
                && record.getCutePoints() == -5 && record.getType() == 10));
        order.verify(draws).insert(any(DrawRecord.class)); order.verify(requests).insert(any(RewardRequest.class));
        order.verify(outbox).insert(argThat(event -> event.getRequestId() == 1001L)); order.verify(manager).commit(any());
    }

    @Test void replayReturnsStoredPrizeAndDoesNotReadOrDebitBalance() throws Exception {
        UserPrizeVO original = new UserPrizeVO(); original.setId(501L); original.setPrizeName("original");
        RewardRequest saved = new RewardRequest(); saved.setStatus("COMPLETED"); saved.setResultJson(json.writeValueAsString(original));
        when(requests.selectOne(any())).thenReturn(saved);
        var result = service.draw(101L, key.toUpperCase(Locale.ROOT));
        assertTrue(result.getReplayed()); assertEquals("original", result.getPrizeName());
        verifyNoInteractions(prizes, ledger, draws, outbox); verify(balances, never()).lockBalance(any());
    }

    @Test void insufficientFundsHasDurableRejectedResultAndCommitsBeforeThrowing() {
        balance.setCutePoints(4);
        assertEquals(40921, assertThrows(BusinessException.class, () -> service.draw(101L, key)).getCode());
        verify(requests).insert(argThat(record -> "REJECTED".equals(record.getStatus())));
        verify(manager).commit(any()); verify(manager, never()).rollback(any());
        verifyNoInteractions(prizes, ledger, draws, outbox);
    }

    @Test void outboxFailurePropagatesAndRollsBackTransaction() {
        when(outbox.insert(any(RewardOutbox.class))).thenThrow(new IllegalStateException("isolated insert failure"));
        assertThrows(IllegalStateException.class, () -> service.draw(101L, key));
        verify(manager).rollback(any()); verify(manager, never()).commit(any());
    }

    @Test void bigWeightsUseLongRangeAndUnlimitedStockIsPreserved() {
        prize.setWeight(Integer.MAX_VALUE).setTotalStock(0).setRemainStock(0);
        PrizePool second = new PrizePool().setId(502L).setPrizeName("second").setPrizeType(4)
                .setWeight(Integer.MAX_VALUE).setTotalStock(0).setRemainStock(0).setStatus(1).setRevision(0L);
        when(prizes.lockMonth("209912")).thenReturn(List.of(prize, second));
        assertNotNull(service.draw(101L, key));
        verify(prizes, never()).updateById(any());
    }

    @Test void disabledAndOutOfStockPrizesCannotConsumePoints() {
        prize.setRemainStock(0);
        assertEquals(40921, assertThrows(BusinessException.class, () -> service.draw(101L, key)).getCode());
        verify(balances, never()).updateById(any()); verifyNoInteractions(ledger, draws, outbox);
    }

    @Test void signWritesCanonicalDateKeyAndLedgerBeforeEvent() {
        service.signIn(101L);
        assertEquals(11, balance.getCutePoints()); assertEquals(11, balance.getTotalEarned());
        verify(requests).insert(argThat(record -> "2099-12-11".equals(record.getRequestKey())
                && "sign:101:2099-12-11".equals(record.getBizId())));
        verify(ledger).insert(argThat(record -> record.getType() == 1 && record.getBeforePoints() == 10 && record.getAfterPoints() == 11));
        verify(outbox).insert(any(RewardOutbox.class));
    }

    @Test void importedSignDoesNotAwardAgain() {
        RewardRequest imported = new RewardRequest(); imported.setLegacyImported(true); imported.setStatus("COMPLETED");
        when(requests.selectOne(any())).thenReturn(imported);
        service.signIn(101L);
        verifyNoInteractions(balances, ledger, prizes, draws, outbox);
    }

    @Test void counterOverflowCannotWriteLedgerOrRequest() {
        balance.setTotalEarned(Integer.MAX_VALUE);
        assertThrows(BusinessException.class, () -> service.signIn(101L));
        verify(balances, never()).updateById(any()); verifyNoInteractions(ledger, outbox);
        verify(requests, never()).insert(any(RewardRequest.class));
    }

    @Test void malformedRequestKeyCannotStartTransaction() {
        for (String value : Arrays.asList(null, "", "abc", "../unsafe", "1-1-1-1-1"))
            assertThrows(BusinessException.class, () -> service.draw(101L, value));
        verifyNoInteractions(manager, users, balances);
    }

    @Test void missingRecoveryResultIsExplicitlyRetryableWithSameKey() {
        assertEquals(40421, assertThrows(BusinessException.class, () -> service.drawResult(101L, key)).getCode());
        verifyNoInteractions(balances, prizes, draws, outbox);
    }

    @Test void bannedAccountIsRejectedBeforeAnyBalanceAccess() {
        doThrow(new BusinessException(40301, 403, "banned fixture")).when(accounts).requireActive(any());
        assertEquals(40301, assertThrows(BusinessException.class, () -> service.draw(101L, key)).getCode());
        verifyNoInteractions(balances, prizes, draws, outbox);
    }
}
