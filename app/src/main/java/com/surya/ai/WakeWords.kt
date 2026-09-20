package com.surya.ai

// नया शब्द जोड़ना हो तो नीचे की सूचियों में बस एक शब्द बढ़ा दें (छोटे अक्षरों में)।
object WakeWords {

    // "हे" वाले शब्द
    val hey: Set<String> = setOf(
        // English
        "hey", "hei", "hee", "heyi", "heyy", "heyyy", "heii", "heey", "heeyy", "heiyy",
        "heyie", "heyee", "heye", "heya", "heyah", "heiya", "heyia", "heyan",
        "hay", "hi", "he", "hai", "hy", "oh", "oye", "ay", "aye", "ae",
        // हिंदी
        "हे", "हेय", "हेयी", "हेई", "हेइ", "हेया", "हेयाह", "हेयान", "हेयिया", "हेइया",
        "हेये", "हेएई", "हाय", "है", "हैय", "ए", "ऐ", "अरे", "ओ", "ओए"
    )

    // "सूर्य" वाले शब्द
    val surya: Set<String> = setOf(
        // English
        "surya", "soorya", "suriya", "sooriya", "suriyaa", "sooriyaa", "suryaa",
        "sorya", "soraya", "soryaa", "suryah", "suriyah", "sooryah", "suryan",
        "suraj", "sooraj", "seriya", "suri", "soori", "suree", "sooree",
        // हिंदी
        "सूर्य", "सूर्या", "सुर्य", "सुर्या", "सूरिया", "सुरिया", "सूरियां", "सुरियां",
        "सोर्य", "सोर्या", "सोरया", "सोरिया", "सूरया", "सूर्याह", "सूरियाह", "सूर्यान",
        "सूर्यन", "सूरज", "सुरज", "सूरजा", "सेरिया", "सेरीया",
        "सूर", "सूरी", "सुरी", "सूरि", "सेरी", "सूरे", "सुरे"
    )

    // RDX वाले शब्द (अलग-अलग बोलने पर जुड़कर बने रूप)
    val rdx: Set<String> = setOf(
        // English
        "rdx", "rdex", "ardx", "ardex", "aardx", "aardex", "aredx", "rdeex", "rdeeex",
        "aredeeex", "areedeeex", "ardeeex",
        // हिंदी
        "आरडीएक्स", "आरडीक्स", "अरडीएक्स", "आरडीऐक्स", "आरडेक्स", "आरडिएक्स", "आरडीएक्ष"
    )
}
