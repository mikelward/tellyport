package tv.tellyport.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileEditorValidationTest {

    @Test
    fun acceptsValidCoordinates() {
        assertNull(coordinateFieldsError(51.5, -0.12, 15.0, 5f))
    }

    @Test
    fun rejectsUnparseableAltitudeInsteadOfDefaulting() {
        assertEquals("Altitude must be a number of meters.", coordinateFieldsError(51.5, -0.12, null, 5f))
    }

    @Test
    fun rejectsNonFiniteAltitudeAndAccuracy() {
        assertEquals("Altitude must be a number of meters.", coordinateFieldsError(51.5, -0.12, Double.NaN, 5f))
        assertEquals("Accuracy must be greater than 0 meters.", coordinateFieldsError(51.5, -0.12, 15.0, Float.NaN))
        assertEquals(
            "Accuracy must be greater than 0 meters.",
            coordinateFieldsError(51.5, -0.12, 15.0, Float.POSITIVE_INFINITY)
        )
        assertEquals("Accuracy must be greater than 0 meters.", coordinateFieldsError(51.5, -0.12, 15.0, null))
    }

    @Test
    fun rejectsOutOfRangeOrNaNLatitudeAndLongitude() {
        assertEquals("Latitude must be between -90 and 90.", coordinateFieldsError(Double.NaN, 0.0, 15.0, 5f))
        assertEquals("Longitude must be between -180 and 180.", coordinateFieldsError(0.0, 181.0, 15.0, 5f))
    }
}
