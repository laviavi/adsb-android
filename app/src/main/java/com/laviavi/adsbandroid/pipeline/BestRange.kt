package com.laviavi.adsbandroid.pipeline

import com.laviavi.adsbandroid.data.BestRangeRecordEntity

/** True when [candidateNm] beats the stored personal-best record, or none exists yet. */
fun isNewBestRange(candidateNm: Double, current: BestRangeRecordEntity?): Boolean =
    current == null || candidateNm > current.distanceNm
