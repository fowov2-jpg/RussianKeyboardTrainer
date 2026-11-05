package dev.russiankeyboard.prototype

data class WritingProfile(val speakerGender: String? = null, val recipientGender: String? = null,
                          val note: String = "", val recipientName: String = "")
