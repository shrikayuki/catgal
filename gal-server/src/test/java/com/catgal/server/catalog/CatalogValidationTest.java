package com.catgal.server.catalog;

import com.catgal.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogValidationTest {
    @Test
    void normalizesNamesAndAppliesPerTableLengthLimits() {
        assertEquals("Key", CatalogValidation.normalizeName(CatalogKind.COMPANY, "  Key  "));
        assertThrows(BusinessException.class,
                () -> CatalogValidation.normalizeName(CatalogKind.TAG, "x".repeat(51)));
        assertThrows(BusinessException.class,
                () -> CatalogValidation.normalizeName(CatalogKind.TAG, "  "));
    }

    @Test
    void tagIdsCannotOverflowTheirDatabaseType() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> CatalogValidation.requireId(CatalogKind.TAG, 2147483648L)).getHttpStatus());
    }

    @Test
    void relationIdsAndRevisionAreValidated() {
        assertThrows(BusinessException.class,
                () -> CatalogValidation.requireLockIds(CatalogKind.COMPANY, null));
        assertThrows(BusinessException.class,
                () -> CatalogValidation.requireLockIds(CatalogKind.TAG, List.of(0L)));
        assertThrows(BusinessException.class, () -> CatalogValidation.requireRevision(null));
        assertThrows(BusinessException.class, () -> CatalogValidation.requireRevision(-1L));
    }
}
