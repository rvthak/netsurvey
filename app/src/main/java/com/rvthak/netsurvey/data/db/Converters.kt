package com.rvthak.netsurvey.data.db

import androidx.room.TypeConverter
import com.rvthak.netsurvey.model.SampleKind
import com.rvthak.netsurvey.telephony.CellDataQuality
import com.rvthak.netsurvey.telephony.CellRole
import com.rvthak.netsurvey.telephony.CellTech

/** Enum ↔ String converters (stored as names so the schema is human-readable). */
class Converters {
    @TypeConverter fun cellTechToString(v: CellTech): String = v.name
    @TypeConverter fun stringToCellTech(v: String): CellTech = CellTech.valueOf(v)

    @TypeConverter fun cellRoleToString(v: CellRole): String = v.name
    @TypeConverter fun stringToCellRole(v: String): CellRole = CellRole.valueOf(v)

    @TypeConverter fun qualityToString(v: CellDataQuality): String = v.name
    @TypeConverter fun stringToQuality(v: String): CellDataQuality = CellDataQuality.valueOf(v)

    @TypeConverter fun sampleKindToString(v: SampleKind): String = v.name
    @TypeConverter fun stringToSampleKind(v: String): SampleKind = SampleKind.valueOf(v)
}
