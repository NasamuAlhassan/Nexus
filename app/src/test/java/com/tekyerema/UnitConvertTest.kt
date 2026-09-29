package com.tekyerema

import com.tekyerema.agent.UnitConvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit conversions answered on the phone: exact factors, speakable answers, money never converted. */
class UnitConvertTest {
    private fun a(s: String) = UnitConvert.answer(s.lowercase())

    @Test fun length() {
        assertEquals("10 kilometres is about 6.21 miles.", a("Convert 10 km to miles"))
        assertEquals("10 kilometres is about 6.21 miles.", a("what is 10 kilometres in miles?"))
        assertEquals("10 miles is about 16.09 kilometres.", a("how many km is 10 miles"))
        assertEquals("There are 2.54 centimetres in an inch.", a("How many centimetres in an inch?"))
        assertEquals("There are about 1.61 kilometres in a mile.", a("how many kilometers are there in a mile"))
        assertEquals("There are 5280 feet in a mile.", a("how many feet in a mile"))
        assertEquals("3 metres is 300 centimetres.", a("3 meters to cm"))
        assertEquals("1000 metres is 1 kilometre.", a("what's 1,000 m in km"))
        assertEquals("100 kilometres is about 62.14 miles.", a("100 km is how many miles"))
    }

    @Test fun heights_are_said_in_feet_and_inches() {
        assertEquals("180 centimetres is about 5 feet 11 inches.", a("180 cm in feet"))
        assertEquals("30.48 centimetres is 1 foot.", a("convert 30.48 cm to feet"))
        assertEquals("2 metres is about 6 feet 7 inches.", a("how tall is 2 metres in feet"))
        assertEquals("5 feet 10 inches is 177.8 centimetres.", a("5 feet 10 in cm"))
        assertEquals("5 feet 10 inches is 177.8 centimetres.", a("what is 5 foot 10 inches in centimetres"))
    }

    @Test fun weight_and_volume() {
        assertEquals("5 kilograms is about 11.02 pounds.", a("how many pounds is 5 kg"))
        assertEquals("1 pound is about 453.6 grams.", a("1 pound in grams"))
        assertEquals("There are 1000 grams in a kilogram.", a("how many grams in a kilogram"))
        assertEquals("2 litres is 2000 millilitres.", a("convert 2 litres to ml"))
        assertEquals("500 millilitres is 0.5 litres.", a("500 ml in litres"))
    }

    @Test fun temperature() {
        assertEquals("30 degrees Celsius is 86 degrees Fahrenheit.", a("What is 30 degrees Celsius in Fahrenheit?"))
        assertEquals("100 degrees Fahrenheit is about 37.8 degrees Celsius.", a("convert 100 fahrenheit to celsius"))
        assertEquals("37 degrees Celsius is 98.6 degrees Fahrenheit.", a("37 degrees in fahrenheit"))
        assertEquals("98 degrees Fahrenheit is about 36.7 degrees Celsius.", a("98 degrees in celsius"))
        assertEquals("minus 5 degrees Celsius is 23 degrees Fahrenheit.", a("minus 5 degrees celsius in fahrenheit"))
        assertEquals("212 degrees Fahrenheit is 100 degrees Celsius.", a("how many degrees celsius is 212 degrees fahrenheit"))
    }

    @Test fun time() {
        assertEquals("3 hours is 180 minutes.", a("how many minutes is 3 hours"))
        assertEquals("There are 86400 seconds in a day.", a("how many seconds in a day"))
        assertEquals("There are 60 minutes in an hour.", a("how many minutes in an hour"))
        assertEquals("There are 7 days in a week.", a("how many days in a week"))
        assertEquals("100 minutes is 1 hour and 40 minutes.", a("100 minutes in hours"))
        assertEquals("90 minutes is 1 hour and 30 minutes.", a("convert 90 minutes to hours"))
        assertEquals("30 minutes is 0.5 hours.", a("30 minutes in hours"))
    }

    @Test fun money_and_anything_unclear_go_on() {
        for (s in listOf("how much is 5 dollars in cedis", "convert 100 pounds to cedis", "how many cedis is 20 dollars",
            "set a timer for 10 minutes", "remind me in 20 minutes", "how many days until christmas", "convert 5 km to kg",
            "how many weeks in a year", "10 km to km", "what is 30 degrees", "convert this to pdf", "how many people live in accra",
            "what is 15 times 12", "how many gallons in a litre", "30 c in f"))
            assertNull(s, a(s))
    }
}
