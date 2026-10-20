package dev.jaoow.cotatrack.api.classification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Encapsulates the resolved classification metadata for a financial asset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetClassification {

    private AssetCategory category;
    private String type;
    private String subType;
    private String sector;
    private String industry;

    public String getCategoryName() {
        return category != null ? category.name() : AssetCategory.EQUITIES.name();
    }
}
