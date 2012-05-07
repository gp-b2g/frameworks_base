/*
 * Copyright (C) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.provider;

import android.content.Context;
import android.util.LocaleNamesParser;

/**
 * get spn by numeric
 * 
 * @hide
 */
public class SpnProvider {

    private static final class SPNObject {
        int mcc;

        int mnc;

        String spn;

        @SuppressWarnings("unused")
        String desc;

        private SPNObject(int mcc, int mnc, String spn, String desc) {
            this.mcc = mcc;
            this.mnc = mnc;
            this.spn = spn;
            this.desc = desc;
        }

        public boolean equals(Object o) {
            if (o == null || !(o instanceof SPNObject))
                return false;

            if (((SPNObject) o).mcc == mcc && ((SPNObject) o).mnc == mnc)
                return true;
            return false;
        }
    }

    private static final SPNObject[] LIST_MCC_MNC_SPN = new SPNObject[] {
            /***********************
             **** Test PLMN 1-1 ****
             ***********************/
            new SPNObject(001, 1, "Test1-1", "Test PLMN 1-1"),

            /***********************
             **** Test PLMN 1-2 ****
             ***********************/
            new SPNObject(001, 2, "Test1-2", "Test PLMN 1-2"),

            /***********************
             **** Test PLMN 2-1 ****
             ***********************/
            new SPNObject(002, 1, "Test2-1", "Test PLMN 2-1"),

            /****************
             **** Greece ****
             ****************/
            new SPNObject(202, 1, "Cosmote", "COSMOTE - Mobile Telecommunications S.A."),
            new SPNObject(202, 5, "Vodafone", "Vodafone Greece"),
            new SPNObject(202, 9, "Wind", "Wind Hella telecommunications S.A."),
            new SPNObject(202, 10, "Wind", "Wind Hella telecommunications S.A."),

            /*********************
             **** Netherlands ****
             *********************/
            new SPNObject(204, 3, "Rabo Mobiel", "KPN"),
            new SPNObject(204, 4, "Vodafone", "Vodafone Netherlands"),
            new SPNObject(204, 8, "KPN", "KPN"),
            new SPNObject(204, 12, "Telfort", "KPN"),
            new SPNObject(204, 16, "T-Mobile / Ben", "T-Mobile Netherlands B.V"),
            new SPNObject(204, 20, "Orange Nederland", "T-Mobile Netherlands B.V"),
            new SPNObject(204, 21, "NS Railinfrabeheer B.V.", "NS Railinfrabeheer B.V."),

            /*****************
             **** Belgium ****
             *****************/
            new SPNObject(206, 1, "Proximus", "Belgacom Mobile"),
            new SPNObject(206, 10, "Mobistar", "France Telecom"),
            new SPNObject(206, 20, "BASE", "KPN"),

            /****************
             **** France ****
             ****************/
            new SPNObject(208, 0, "Orange", "Orange"),
            new SPNObject(208, 1, "France Telecom Mobile", "France Orange"),
            new SPNObject(208, 2, "Orange", "Orange"),
            new SPNObject(208, 5, "Globalstar Europe", "Globalstar Europe"),
            new SPNObject(208, 6, "Globalstar Europe", "Globalstar Europe"),
            new SPNObject(208, 7, "Globalstar Europe", "Globalstar Europe"),
            new SPNObject(208, 10, "SFR", "SFR"),
            new SPNObject(208, 11, "SFR", "SFR"),
            new SPNObject(208, 20, "Bouygues", "Bouygues Telecom"),
            new SPNObject(208, 21, "Bouygues", "Bouygues Telecom"),

            /*****************
             **** Andorra ****
             *****************/
            new SPNObject(213, 3, "Mobiland", "Servei De Tele. DAndorra"),

            /***************
             **** Spain ****
             ***************/
            new SPNObject(214, 1, "Vodafone", "Vodafone Spain"),
            new SPNObject(214, 3, "Orange", "France Telcom Espana SA"),
            new SPNObject(214, 4, "Yoigo", "Xfera Moviles SA"),
            new SPNObject(214, 5, "TME", "Telefonica Moviles Espana"),
            new SPNObject(214, 6, "Vodafone", "Vodafone Spain"),
            new SPNObject(214, 7, "movistar", "Telefonica Moviles Espana"),
            new SPNObject(214, 9, "Orange", "France Telcom Espana SA"),

            /*****************
             **** Hungary ****
             *****************/
            new SPNObject(216, 20, "Pannon", "Pannon GSM Tavkozlesi Zrt."),
            new SPNObject(216, 30, "T-Mobile", "Magyar Telkom Plc"),
            new SPNObject(216, 70, "Vodafone", "Vodafonei Magyarorszag Zrt."),

            /********************************
             **** Bosnia and Herzegovina ****
             ********************************/
            new SPNObject(218, 3, "ERONET", "Public Enterprise Croatian telecom Ltd."),
            new SPNObject(218, 5, "m:tel", "RS Telecommunications JSC Banja Luka"),
            new SPNObject(218, 90, "BH Mobile", "BH Telecom"),

            /*****************
             **** Croatia ****
             *****************/
            new SPNObject(219, 1, "T-Mobile", "T-Mobile Croatia"),
            new SPNObject(219, 2, "Tele2", "Tele2"),
            new SPNObject(219, 10, "VIPnet", "Vipnet"),

            /****************
             **** Serbia ****
             ****************/
            new SPNObject(220, 1, "Telenor", "Telenor Serbia"),
            new SPNObject(220, 3, "Telekom Sribija", "Telekom Srbija"),
            new SPNObject(220, 5, "VIP Mobile", "VIP Mobile"),

            /***************
             **** Italy ****
             ***************/
            new SPNObject(222, 1, "TIM", "Telecom Italiz SpA"),
            new SPNObject(222, 2, "Elsacom", "Elsacom"),
            new SPNObject(222, 10, "Vodafone", "Vodafone Omnitel N.V."),
            new SPNObject(222, 30, "RRI", "Rete  Ferroviaria Italiana"),
            new SPNObject(222, 88, "Wind", "Wind Telecomunicazioni SpA"),
            new SPNObject(222, 99, "3 Italia", "Hutchison 3G"),

            /*****************
             **** Romania ****
             *****************/
            new SPNObject(226, 1, "Vodafone", "Vodafone Romania"),
            new SPNObject(226, 3, "Cosmote", "Cosmote Romania"),
            new SPNObject(226, 5, "DIGI.mobil", "RCS&RDS"),
            new SPNObject(226, 10, "Orange", "Orange Romania"),

            /*********************
             **** Switzerland ****
             *********************/
            new SPNObject(228, 1, "Swisscom", "Swisscom Ltd"),
            new SPNObject(228, 2, "Sunrise", "Sunrise Communications AG"),
            new SPNObject(228, 3, "Orange", "Orange Communications SA"),
            new SPNObject(228, 6, "SBB AG", "SBB AG"),
            new SPNObject(228, 7, "IN&Phone", "IN&Phone SA"),
            new SPNObject(228, 8, "Tele2", "Tele2 Telecommunications AG"),

            /************************
             **** Czech Republic ****
             ************************/
            new SPNObject(230, 1, "T-Mobile", "T-Mobile Czech Republic"),
            new SPNObject(230, 2, "EUROTEL PRAHA", "Telefonica O2 Czech Republic"),
            new SPNObject(230, 3, "OSKAR", "Vodafone Czech Republic"),
            new SPNObject(230, 98, "CZDC s.o.", "CZDC s.o."),

            /******************
             **** Slovakia ****
             ******************/
            new SPNObject(231, 1, "Orange", "Orange Slovensko"),
            new SPNObject(231, 2, "T-Mobile", "T-Mobile Slovensko"),
            new SPNObject(231, 4, "T-Mobile", "T-Mobile Slovensko"),
            new SPNObject(231, 6, "O2", "Telefonica O2 Slovakia"),

            /*****************
             **** Austria ****
             *****************/
            new SPNObject(232, 1, "A1", "Mobilkom Austria"),
            new SPNObject(232, 3, "T-Mobile", "T-Mobile Austria"),
            new SPNObject(232, 5, "Orange", "Orange Austria"),
            new SPNObject(232, 7, "T-Mobile", "T-Mobile Austria"),
            new SPNObject(232, 10, "3", "Hutchison 3G"),

            /************************
             **** United Kingdom ****
             ************************/
            new SPNObject(234, 0, "BT", "British Telecom"),
            new SPNObject(234, 1, "UK01", "Mapesbury Communications Ltd."),
            new SPNObject(234, 2, "O2", "O2"),
            new SPNObject(234, 3, "Jersey Telenet", "Jersey Telnet"),
            new SPNObject(234, 10, "O2", "Telefonica O2 UK Limited"),
            new SPNObject(234, 11, "O2", "Telefonica Europe"),
            new SPNObject(234, 12, "Railtrack", "Network Rail Infrastructure Ltd"),
            new SPNObject(234, 15, "Vodafone", "Vodafone United Kingdom"),
            new SPNObject(234, 16, "Opal Telecom Ltd", "Opal Telecom Ltd"),
            new SPNObject(234, 18, "Cloud9", "Wire9 Telecom plc"),
            new SPNObject(234, 19, "Telaware", "Telaware plc"),
            new SPNObject(234, 20, "3", "Hutchison 3G UK Ltd"),
            new SPNObject(234, 30, "T-Mobile", "T-Mobile"),
            new SPNObject(234, 31, "Virgin", "Virgin Mobile"),
            new SPNObject(234, 32, "Virgin", "Virgin Mobile"),
            new SPNObject(234, 33, "Orange", "Orange PCS Ltd"),
            new SPNObject(234, 34, "Orange", "Orange PCS Ltd"),
            new SPNObject(234, 50, "JT-Wave", "Jersey Telecoms"),
            new SPNObject(234, 55, "Cable & Wireless Guernsey / Sure Mobile (Jersey)",
                    "Cable & Wireless Guernsey / Sure Mobile (Jersey)"),
            new SPNObject(234, 58, "Manx Telecom", "Manx Telecom"),
            new SPNObject(234, 75, "Inquam", "Inquam Telecom (Holdings) Ltd"),

            /*****************
             **** Denmark ****
             *****************/
            new SPNObject(238, 1, "TDC", "TDC A/S"),
            new SPNObject(238, 2, "Sonofon", "Telenor"),
            new SPNObject(238, 6, "3", "Hi3G Denmark ApS"),
            new SPNObject(238, 30, "Telia", "Telia Nattjanster Norden AB"),
            new SPNObject(238, 70, "Tele2", "Telenor"),

            /****************
             **** Sweden ****
             ****************/
            new SPNObject(240, 1, "Telia", "TeliaSonera Mobile Networks"),
            new SPNObject(240, 2, "3", "3"),
            new SPNObject(240, 4, "3G Infrastructure Services", "3G Infrastructure Services"),
            new SPNObject(240, 5, "Sweden 3G", "Sweden 3G"),
            new SPNObject(240, 6, "Telenor", "Telenor"),
            new SPNObject(240, 7, "Tele2", "Tele2 AB"),
            new SPNObject(240, 8, "Telenor", "Telenor"),
            new SPNObject(240, 21, "Banverket", "Banverket"),

            /****************
             **** Norway ****
             ****************/
            new SPNObject(242, 1, "Telenor", "Telenor"),
            new SPNObject(242, 2, "NetCom", "NetCom GSM"),
            new SPNObject(242, 5, "Network Norway", "Network Norway"),
            new SPNObject(242, 20, "Jernbaneverket AS", "Jernbaneverket AS"),

            /*****************
             **** Finland ****
             *****************/
            new SPNObject(244, 3, "DNA", "DNA Oy"),
            new SPNObject(244, 5, "Elisa", "Elisa Oyj"),
            new SPNObject(244, 12, "DNA Oy", "DNA Oy"),
            new SPNObject(244, 14, "AMT", "Alands Mobiltelefon"),
            new SPNObject(244, 91, "Sonera", "TeliaSonera Finland Oyj"),

            /*******************
             **** Lithuania ****
             *******************/
            new SPNObject(246, 1, "Omnitel", "Omnitel"),
            new SPNObject(246, 2, "BITE", "UAB Bite Lietuva"),
            new SPNObject(246, 3, "Tele 2", "Tele 2"),

            /****************
             **** Latvia ****
             ****************/
            new SPNObject(247, 1, "LMT", "Latvian Mobile Telephone"),
            new SPNObject(247, 2, "Tele2", "Tele2"),
            new SPNObject(247, 5, "Bite", "Bite Latvija"),

            /*****************
             **** Estonia ****
             *****************/
            new SPNObject(248, 1, "EMT", "Estonian Mobile Telecom"),
            new SPNObject(248, 2, "Elisa", "Elisa Eesti"),
            new SPNObject(248, 3, "Tele 2", "Tele 2 Eesti"),

            /***************************
             **** Russia Federation ****
             ***************************/
            new SPNObject(250, 1, "MTS", "Mobile Telesystems"),
            new SPNObject(250, 2, "MegaFon", "MegaFon OJSC"),
            new SPNObject(250, 3, "NCC", "Nizhegorodskaya Cellular Communications"),
            new SPNObject(250, 5, "ETK", "Yeniseytelecom"),
            new SPNObject(250, 7, "SMARTS", "Zao SMARTS"),
            new SPNObject(250, 12, "Baykalwstern",
                    "Baykal Westcom/New Telephone Company/Far Eastern Cellular"),
            new SPNObject(250, 14, "SMARTS", "SMARTS Ufa"),
            new SPNObject(250, 16, "NTC", "New Telephone Company"),
            new SPNObject(250, 17, "Utel", "JSC Uralsvyazinform"),
            new SPNObject(250, 19, "INDIGO", "INDIGO"),
            new SPNObject(250, 20, "Tele2", "Tele2"),
            new SPNObject(250, 23, "Mobicom - Novosibirsk", "Mobicom - Novosibirsk"),
            new SPNObject(250, 39, "Utel", "Uralsvyazinform"),
            new SPNObject(250, 99, "Beeline", "OJSC VimpelCom"),

            /*****************
             **** Ukraine ****
             *****************/
            new SPNObject(255, 1, "MTS", "Ukrainian Mobile Communications"),
            new SPNObject(255, 2, "Beeline", "Ukrainian Radio Systems"),
            new SPNObject(255, 3, "Kyivstar", "Kyivstar GSM JSC"),
            new SPNObject(255, 5, "Golden Telecom", "Golden Telecom"),
            new SPNObject(255, 6, "life:)", "Astelit"),
            new SPNObject(255, 7, "Utel", "Ukrtelecom"),

            /*****************
             **** Belarus ****
             *****************/
            new SPNObject(257, 1, "Velcom", "Velcom"),
            new SPNObject(257, 2, "MTS", "JLLC Mobile TeleSystems"),
            new SPNObject(257, 4, "life:)", "Belarussian Telecommunications Network"),

            /*****************
             **** Moldova ****
             *****************/
            new SPNObject(259, 1, "Orange", "Orange Moldova"),
            new SPNObject(259, 2, "Moldcell", "Moldcell"),
            new SPNObject(259, 4, "Eventis", "Eventis Telecom"),

            /****************
             **** Poland ****
             ****************/
            new SPNObject(260, 1, "Plus", "Polkomtel"),
            new SPNObject(260, 2, "Era", "Polska Telefonia Cyfrowa (PTC)"),
            new SPNObject(260, 3, "Orange", "PTK Centertel"),
            new SPNObject(260, 6, "Play", "P4 Sp. zo.o"),
            new SPNObject(260, 12, "Cyfrowy Polsat", "Cyfrowy Polsat"),
            new SPNObject(260, 14, "Sferia", "Sferia S.A."),

            /*****************
             **** Germany ****
             *****************/
            new SPNObject(262, 1, "T-Mobile", "T-Mobile"),
            new SPNObject(262, 2, "Vodafone", "Vodafone D2 GmbH"),
            new SPNObject(262, 3, "E-Plus", "E-Plus Mobilfunk"),
            new SPNObject(262, 4, "Vodafone", "Vodafone"),
            new SPNObject(262, 5, "E-Plus", "E-Plus Mobilfunk"),
            new SPNObject(262, 6, "T-Mobile", "T-Mobile"),
            new SPNObject(262, 7, "O2", "O2 (Germany) GmbH & Co. OHG"),
            new SPNObject(262, 8, "O2", "O2"),
            new SPNObject(262, 9, "Vodafone", "Vodafone"),
            new SPNObject(262, 10, "Arcor AG & Co", "Arcor AG * Co"),
            new SPNObject(262, 11, "O2", "O2"),
            new SPNObject(262, 15, "Airdata", "Airdata"),
            new SPNObject(262, 60, "DB Telematik", "DB Telematik"),
            new SPNObject(262, 76, "Siemens AG", "Siemens AG"),
            new SPNObject(262, 77, "E-Plus", "E-Plus"),

            /*******************
             **** Gibraltar ****
             *******************/
            new SPNObject(266, 1, "GibTel", "Gibraltar Telecoms"),

            /******************
             **** Portugal ****
             ******************/
            new SPNObject(268, 1, "Vodafone", "Vodafone Portugal"),
            new SPNObject(268, 3, "Optimus", "Sonaecom - Servicos de Comunicacoes, S.A."),
            new SPNObject(268, 6, "TMN", "Telecomunicacoes Moveis Nacionais"),

            /********************
             **** Luxembourg ****
             ********************/
            new SPNObject(270, 1, "LuxGSM", "P&T Luxembourg"),
            new SPNObject(270, 77, "Tango", "Tango SA"),
            new SPNObject(270, 99, "Voxmobile", "VOXmobile S.A"),

            /*****************
             **** Ireland ****
             *****************/
            new SPNObject(272, 1, "Vodafone", "Vodafone Ireland"),
            new SPNObject(272, 2, "O2", "O2 Ireland"),
            new SPNObject(272, 3, "Meteor", "Meteor"),
            new SPNObject(272, 5, "3", "Hutchison 3G IReland limited"),

            /*****************
             **** Iceland ****
             *****************/
            new SPNObject(274, 1, "Siminn", "Iceland Telecom"),
            new SPNObject(274, 2, "Vodafone", "iOg fjarskipti hf"),
            new SPNObject(274, 4, "Viking", "IMC Island ehf"),
            new SPNObject(274, 7, "IceCell", "IceCell ehf"),
            new SPNObject(274, 11, "Nova", "Nova ehf"),

            /*****************
             **** Albania ****
             *****************/
            new SPNObject(276, 1, "AMC", "Albanian Mobile Communications"),
            new SPNObject(276, 2, "Vodafone", "Vodafone Albania"),
            new SPNObject(276, 3, "Eagle Mobile", "Eagle Mobile"),

            /***************
             **** Malta ****
             ***************/
            new SPNObject(278, 1, "Vodafone", "Vodafone Malta"),
            new SPNObject(278, 21, "GO", "Mobisle Communications Limited"),
            new SPNObject(278, 77, "Melita", "Melita Mobile Ltd. (3G Telecommunictaions Limited"),

            /****************
             **** Cyprus ****
             ****************/
            new SPNObject(280, 1, "Cytamobile-Vodafone", "Cyprus Telcommunications Auth"),
            new SPNObject(280, 10, "MTN", "Areeba Ltde"),

            /*****************
             **** Georgia ****
             *****************/
            new SPNObject(282, 1, "Geocell", "Geocell Limited"),
            new SPNObject(282, 2, "Magti", "Magticom GSM"),
            new SPNObject(282, 4, "Beeline", "Mobitel LLC"),
            new SPNObject(282, 67, "Aquafon", "Aquafon"),
            new SPNObject(282, 88, "A-Mobile", "A-Mobile"),

            /*****************
             **** Armenia ****
             *****************/
            new SPNObject(283, 1, "Beeline", "ArmenTel"),
            new SPNObject(283, 5, "VivaCell-MTS", "K Telecom CJSC"),

            /******************
             **** Bulgaria ****
             ******************/
            new SPNObject(284, 1, "M-TEL", "Mobiltel"),
            new SPNObject(284, 3, "Vivatel", "BTC"),
            new SPNObject(284, 5, "GLOBUL", "Cosmo Bulgaria Mobile"),

            /****************
             **** Turkey ****
             ****************/
            new SPNObject(286, 1, "Turkcell", "Turkcell lletisim Hizmetleri A.S."),
            new SPNObject(286, 2, "Vodafone", "Vodafone Turkey"),
            new SPNObject(286, 3, "Avea", "Avea"),

            /********************************
             **** Faroe Islands (Demark) ****
             ********************************/
            new SPNObject(288, 1, "Faroese", "Faroese Telecom"),
            new SPNObject(288, 2, "Vodafone", "Vodafone Faroe Islands"),

            /*******************
             **** Greenland ****
             *******************/
            new SPNObject(290, 1, "TELE Greenland A/S", "Tele Greenland A/S"),

            /********************
             **** San Marino ****
             ********************/
            new SPNObject(292, 1, "PRIMA", "San Marino Telecom"),

            /******************
             **** Slovenia ****
             ******************/
            new SPNObject(293, 40, "Si.mobil", "SI.MOBIL d.d"),
            new SPNObject(293, 41, "Si.mobil", "Mobitel D.D."),
            new SPNObject(293, 64, "T-2", "T-2 d.o.o."),
            new SPNObject(293, 70, "Tusmobil", "Tusmobil d.o.o."),

            /*******************************
             **** Republic of Macedonia ****
             *******************************/
            new SPNObject(294, 1, "T-Mobile", "T-Mobile Makedonija"),
            new SPNObject(294, 2, "Cosmofon", "Cosmofon"),
            new SPNObject(294, 2, "VIP Operator", "VIP Operator"),

            /***********************
             **** Liechtenstein ****
             ***********************/
            new SPNObject(295, 1, "Swisscom", "Swisscom Schweiz AG"),
            new SPNObject(295, 2, "Orange", "Orange Liechtenstein AG"),
            new SPNObject(295, 5, "FL1", "Mobilkom Liechtenstein AG"),
            new SPNObject(295, 77, "Tele 2", "Belgacom"),

            /********************
             **** Montenegro ****
             ********************/
            new SPNObject(297, 1, "ProMonte", "ProMonte GSM"),
            new SPNObject(297, 2, "T-Mobile", "T-Mobile Montenegro LLC"),
            new SPNObject(297, 3, "m:tel CG", "MTEL CG"),

            /****************
             **** Canada ****
             ****************/
            new SPNObject(302, 370, "Fido", "Fido"),
            new SPNObject(302, 620, "ICE Wireless", "ICE Wireless"),
            new SPNObject(302, 720, "Rogers Wireless", "Rogers Wireless"),

            /********************************************
             **** Saint Pierre and Miquelon (France) ****
             ********************************************/
            new SPNObject(308, 1, "Ameris", "St. Pierre-et-Miquelon Telecom"),

            /****************************************
             **** United States of America, Guam ****
             ****************************************/
            new SPNObject(310, 20, "Union Telephony Company", "Union Telephony Company"),
            new SPNObject(310, 26, "T-Mobile", "T-Mobile"),
            new SPNObject(310, 30, "Centennial", "Centennial Communications"),
            new SPNObject(310, 38, "AT&T", "AT&T Mobility"),
            new SPNObject(310, 40, "Concho", "Concho Cellular Telephony Co., Inc."),
            new SPNObject(310, 46, "SIMMETRY", "TMP Corp"),
            new SPNObject(310, 70, "AT&T", "AT&T"),
            new SPNObject(310, 80, "Corr", "Corr Wireless Communications LLC"),
            new SPNObject(310, 90, "AT&T", "AT&T"),
            new SPNObject(310, 100, "Plateau Wireless", "New Mexico RSA 4 East Ltd. Partnership"),
            new SPNObject(310, 110, "PTI Pacifica", "PTI Pacifica Inc."),
            new SPNObject(310, 150, "AT&T", "AT&T"),
            new SPNObject(310, 170, "AT&T", "AT&T"),
            new SPNObject(310, 180, "West Cen", "West Central"),
            new SPNObject(310, 190, "Dutch Harbor", "Alaska Wireless Communications, LLC"),
            new SPNObject(310, 260, "T-Mobile", "T-Mobile"),
            new SPNObject(310, 300, "Get Mobile Inc", "Get Mobile Inc"),
            new SPNObject(310, 311, "Farmers Wireless", "Farmers Wireless"),
            new SPNObject(310, 330, "Cell One", "Cellular One"),
            new SPNObject(310, 340, "Westlink", "Westlink Communications"),
            new SPNObject(310, 380, "AT&T", "AT&T"),
            new SPNObject(310, 400, "i CAN_GSM", "Wave runner LLC (Guam)"),
            new SPNObject(310, 410, "AT&T", "AT&T"),
            new SPNObject(310, 420, "Cincinnati Bell", "Cincinnati Bell Wireless"),
            new SPNObject(310, 430, "Alaska Digitel", "Alaska Digitel"),
            new SPNObject(310, 450, "Viaero", "Viaero Wireless"),
            new SPNObject(310, 460, "Simmetry", "TMP Corporation"),
            new SPNObject(310, 540, "Oklahoma Western", "Oklahoma Western Telephone Company"),
            new SPNObject(310, 560, "AT&T", "AT&T"),
            new SPNObject(310, 570, "Cellular One", "MTPCS, LLC"),
            new SPNObject(310, 590, "Alltel", "Alltel Communications Inc"),
            new SPNObject(310, 610, "Epic Touch", "Elkhart Telephone Co."),
            new SPNObject(310, 620, "Coleman County Telecom", "Coleman County Telecommunications"),
            new SPNObject(310, 640, "Airadigim", "Airadigim Communications"),
            new SPNObject(310, 650, "Jasper", "Jasper wireless, inc"),
            new SPNObject(310, 680, "AT&T", "AT&T"),
            new SPNObject(310, 770, "i wireless", "lows Wireless Services"),
            new SPNObject(310, 790, "PinPoint", "PinPoint Communications"),
            new SPNObject(310, 830, "Caprock", "Caprock Cellular"),
            new SPNObject(310, 850, "Aeris", "Aeris Communications, Inc."),
            new SPNObject(310, 870, "PACE", "Kaplan Telephone Company"),
            new SPNObject(310, 880, "Advantage", "Advantage Cellular Systems"),
            new SPNObject(310, 890, "Unicel", "Rural cellular Corporation"),
            new SPNObject(310, 900, "Taylor", "Taylor Telecommunications"),
            new SPNObject(310, 910, "First Cellular", "First Cellular of Southern Illinois"),
            new SPNObject(310, 950, "XIT Wireless", "Texas RSA 1 dba XIT Cellular"),
            new SPNObject(310, 970, "Globalstar", "Globalstar"),
            new SPNObject(310, 980, "AT&T", "AT&T"),
            new SPNObject(311, 10, "Chariton Valley", "Chariton Valley Communications"),
            new SPNObject(311, 20, "Missouri RSA 5 Partnership", "Missouri RSA 5 Partnership"),
            new SPNObject(311, 30, "Indigo Wireless", "Indigo Wireless"),
            new SPNObject(311, 40, "Commnet Wireless", "Commnet Wireless"),
            new SPNObject(311, 50, "Wikes Cellular", "Wikes Cellular"),
            new SPNObject(311, 60, "Farmers Cellular", "Farmers Cellular Telephone"),
            new SPNObject(311, 70, "Easterbrooke", "Easterbrooke Cellular Corporation"),
            new SPNObject(311, 80, "Pine Cellular", "Pine Telephone Company"),
            new SPNObject(311, 90, "Long Lines Wireless", "Long Lines Wireless LLC"),
            new SPNObject(311, 100, "High Plains Wireless", "High Plains Wireless"),
            new SPNObject(311, 110, "High Plains Wireless", "High Plains Wireless"),
            new SPNObject(311, 130, "Cell One Amarillo", "Cell One Amarillo"),
            new SPNObject(311, 150, "Wilkes Cellular", "Wilkes Cellular"),
            new SPNObject(311, 170, "PetroCom", "Broadpoint Inc"),
            new SPNObject(311, 180, "AT&T", "AT&T"),
            new SPNObject(311, 210, "Farmers Cellular", "Farmers Cellular Telephone"),

            /*********************
             **** Puerto Rico ****
             *********************/
            new SPNObject(330, 11, "Claro", "Puerto Rico Telephony Company"),

            /****************
             **** Mexico ****
             ****************/
            new SPNObject(334, 2, "Telcel", "America Movil"),
            new SPNObject(334, 3, "movistar", "Pegaso Comunicaciones y Sistemas"),

            /*****************
             **** Jamaica ****
             *****************/
            new SPNObject(338, 20, "Cable & Wireless", "Cable & Wireless"),
            new SPNObject(338, 50, "Digicel", "Digicel (Jamaica) Limited"),
            new SPNObject(338, 70, "Claro", "Oceanic Digital Jamaica Limited"),

            /*****************************
             **** Guadeloupe (France) ****
             *****************************/
            new SPNObject(340, 1, "Orange", "Orange Caraibe Mobiles"),
            new SPNObject(340, 2, "Outremer", "Outremer Telecom"),
            new SPNObject(340, 3, "Teleceli", "Saint Martin et Saint Barthelemy Telcell Sarl"),
            new SPNObject(340, 8, "MIO GSM", "Dauphin Telecom"),
            new SPNObject(340, 20, "Digicel", "DIGICEL Antilles Franccaise Guyane"),

            /******************
             **** Barbados ****
             ******************/
            new SPNObject(342, 600, "bmobile", "cable &Wireless Barbados Ltd."),
            new SPNObject(342, 750, "Digicel", "Digicel (Jamaica) Limited"),

            /*****************************
             **** Antigua and Barbuda ****
             *****************************/
            new SPNObject(344, 30, "APUA", "Antigua Public Utilities Authority"),
            new SPNObject(344, 920, "bmobile",
                    "Cable & Wireless Caribbean Cellular (Antigua) Limited"),
            new SPNObject(344, 930, "Digicel", "Antigua Wireless Ventures Limited"),

            /*****************************************
             **** Cayman Islands (United Kingdom) ****
             *****************************************/
            new SPNObject(346, 50, "Digicel", "Digicel Cayman Ltd."),
            new SPNObject(346, 140, "Cable & Wireless",
                    "Cable & Wireless (Caymand Islands) Limited"),

            /*************************************************
             **** British Virgin Islands (United Kingdom) ****
             *************************************************/
            new SPNObject(348, 170, "Cable & Wireless", "Cable & Wireless (West Indies)"),
            new SPNObject(348, 570, "Caribbean Cellular Telephone", "Caribbean Cellular Telephone"),

            /*****************
             **** Bermuda ****
             *****************/
            new SPNObject(350, 1, "Digicel Bermuda",
                    "Telecommunications (Bermuda & West Indies) Ltd"),
            new SPNObject(350, 2, "Mobility", "M3 wireless"),
            new SPNObject(350, 38, "Digicel", "Digicel"),

            /*****************
             **** Grenada ****
             *****************/
            new SPNObject(352, 30, "Digicel", "Digicel Grenada Ltd."),
            new SPNObject(352, 110, "Cable & Wireless", "Cable & Wireless Grenada Ltd."),

            /******************************
             **** Netherlands Antilles ****
             ******************************/
            new SPNObject(362, 51, "Telcell", "Telcell N.V."),
            new SPNObject(362, 69, "Digicel", "Curacao Telecom N.V."),
            new SPNObject(362, 91, "UTS", "Setel NV"),

            /********************************************
             **** Aruba (Kingdom of the Netherlands) ****
             ********************************************/
            new SPNObject(363, 1, "SETAR", "SETAR (Servicio di Telecommunication diAruba"),
            new SPNObject(363, 20, "Digicell", "Digicell"),

            /*****************
             **** Bahamas ****
             *****************/
            new SPNObject(364, 390, "BaTelCo", "The Bahamas Telecommunications Company Ltd"),

            /***********************************
             **** Anguilla (United Kingdom) ****
             ***********************************/
            new SPNObject(365, 10, "Weblinks Limited", "Weblinks Limited"),

            /**************
             **** Cuba ****
             **************/
            new SPNObject(368, 1, "ETECSA", "Empresa de Telecomunicaciones de Cuba, SA"),

            /****************************
             **** Dominican Republic ****
             ****************************/
            new SPNObject(370, 1, "Orange", "Orange Dominicana"),
            new SPNObject(370, 2, "Claro", "Compania Dominicana de Telefonos, C por"),
            new SPNObject(370, 4, "ViVa", "Centennial Dominicana"),

            /***************
             **** Haiti ****
             ***************/
            new SPNObject(372, 10, "Comcel / Voila", "Comcel / Voila"),
            new SPNObject(372, 50, "Digicel", "Digicel"),

            /*****************************
             **** Trinidad and Tobaga ****
             *****************************/
            new SPNObject(374, 12, "bmobile", "TSTT"),
            new SPNObject(374, 13, "Digicel", "Digicel"),

            /********************
             **** Azerbaijan ****
             ********************/
            new SPNObject(400, 1, "Azercell", "Azercell"),
            new SPNObject(400, 2, "Bakcell", "Bakcell"),
            new SPNObject(400, 4, "Nar Mobile", "Azerfon"),

            /********************
             **** Kazakhstan ****
             ********************/
            new SPNObject(401, 1, "Beeline", "KaR-TeL LLP"),
            new SPNObject(401, 2, "K'Cell", "GSM Kazakhstan Ltdx."),
            new SPNObject(401, 77, "Mobile Telecom Service", "Mobile Telecom Service LLP"),

            /****************
             **** Bhutan ****
             ****************/
            new SPNObject(402, 11, "B-Mobile", "B-Mobile"),
            new SPNObject(402, 77, "TashiCell", "Tashi InfoComm Limited"),

            /***************
             **** India ****
             ***************/
            new SPNObject(404, 1, "Vodafone - Haryana", "Vodafone"),
            new SPNObject(404, 2, "Airtel - Punjab", "Bharti Airtel"),
            new SPNObject(404, 3, "Airtel - Himachal Pradesh", "Bharti Airtel"),
            new SPNObject(404, 4, "Idea - Delhi", "Idea cellular Limited "),
            new SPNObject(404, 5, "Vodafone - Gujarat", "Vodafone"),
            new SPNObject(404, 7, "Idea - Andhra Pradesh", "Idea Cellular Limited"),
            new SPNObject(404, 9, "Reliance - Assam", "Reliance Communications"),
            new SPNObject(404, 10, "Airtel Delhi", "Bharti Airtel"),
            new SPNObject(404, 11, "Vodafone - Delhi", "Vodafone"),
            new SPNObject(404, 12, "Idea - Haryana", "Idea Cellular Limited"),
            new SPNObject(404, 13, "Vodafone - Andhra Pradesh", "Vodafone"),
            new SPNObject(404, 14, "Spice Telecom - Punjab", "Spice Communications Limited"),
            new SPNObject(404, 15, "Vodafone - Uttar Pradesh (East)", "Vodafone"),
            new SPNObject(404, 16, "Airtel - North East", "Bharti Airtel"),
            new SPNObject(404, 17, "Aircel - West Bengal", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 18, "Reliance - Himachal Pradesh", "Reliance Communications"),
            new SPNObject(404, 19, "Idea - Kerala", "Idea Cellular Limited"),
            new SPNObject(404, 20, "Vodafone - Mumbai", "Vodafone"),
            new SPNObject(404, 21, "BPL Mobile Mumbai", "BPL Mobile Mumbai"),
            new SPNObject(404, 22, "Idea - Maharashtra", "Idea Cellular Limited"),
            new SPNObject(404, 24, "Idea - Gujarat", "Idea Cellular Limited"),
            new SPNObject(404, 25, "Aircel - Bihar", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 27, "Vodafone - Maharashtra", "Vodafone"),
            new SPNObject(404, 28, "Aircel - Orissa", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 29, "Aircel - Assam", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 30, "Vodafone - Kolkata", "Vodafone"),
            new SPNObject(404, 31, "Airtel - Kolkata", "Bharti Airtel"),
            new SPNObject(404, 33, "Aircel - North East", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 34, "BSNL - Haryana", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 35, "Aircel - Himachal Pradesh", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 36, "Reliance - Bihar", "Reliance Communications"),
            new SPNObject(404, 37, "Aircel - Jammu & Kashmir", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 38, "BSNL - Assam", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 40, "Airtel - Chennai", "Bharti Airtel"),
            new SPNObject(404, 41, "Aircel - Chennai", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 42, "Aircel - Tamilnadu", "Dishnet Wireless/Aircel"),
            new SPNObject(404, 43, "Vodafone - Tamilnadu", "Vodafone"),
            new SPNObject(404, 44, "Spice Telecom - Karnataka", "Spice Communications Limited"),
            new SPNObject(404, 46, "Vodafone - Kerala", "Vodafone"),
            new SPNObject(404, 49, "Airtel - Andhra Pradesh", "Bharti Airtel"),
            new SPNObject(404, 50, "Reliance - North East", "Reliance Communications"),
            new SPNObject(404, 51, "BSNL - Himachal Pradeshl", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 52, "Reliance - Orissa", "Reliance Communications"),
            new SPNObject(404, 53, "BSNL - Punjab", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 54, "BSNL - Uttar Pradesh (West)", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 55, "BSNL - Uttar Pradesh (East)", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 56, "Idea - Uttar Pradesh West", "Idea Cellular Limited"),
            new SPNObject(404, 57, "BSNL - Gujarat", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 58, "BSNL - Madhya Pradesh", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 59, "BSNL - Rajasthan", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 60, "Vodafone - Rajasthan", "Vodafone"),
            new SPNObject(404, 62, "BSNL - Jammu & Kashmir", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 64, "BSNL - Chennai", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 66, "BSNL - Maharashtra", "Bharat Sanchar Nigam Limited"),
            new SPNObject(404, 67, "Vodafone - West Bengal", "Vodafone"),
            new SPNObject(404, 68, "MTNL - Delhi", "Mahanagar Telephone Nigam Ltd"),
            new SPNObject(404, 69, "MTNL - Mumbai", "Mahanagar Telephone Nigam Ltd"),
            new SPNObject(404, 70, "Airtel - Rajasthan", "Bharti Airtel"),
            new SPNObject(404, 71, "BSNL - Karnataka", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 72, "BSNL - Kerala", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 73, "BSNL - Andhra Pradesh", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 74, "BSNL - West Bengal", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 75, "BSNL - Bihar", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 76, "BSNL - Orissa", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 77, "BSNL - North East", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 78, "Idea - Madhya Pradesh", "Idea Cellular Limited"),
            new SPNObject(404, 79, "BSNL - Andaman Nicobar", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 80, "BSNL - Tamilnadu", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 81, "BSNL - Kolkata", "Bharti Sanchar Nigam Limited"),
            new SPNObject(404, 82, "Idea - Himachal Pradesh", "Idea Cellular Limited"),
            new SPNObject(404, 83, "Reliance - Kolkata", "Reliance Communications"),
            new SPNObject(404, 84, "Vodafone - Chennai", "Vodafone"),
            new SPNObject(404, 85, "Reliance - West Bengal", "Reliance Communications"),
            new SPNObject(404, 86, "Vodafone - Karnataka", "Vodafone"),
            new SPNObject(404, 87, "Idea - Rajasthan", "Idea Cellular Limited"),
            new SPNObject(404, 88, "Vodafone - Punjab", "Vodafone"),
            new SPNObject(404, 89, "Idea - Uttar Pradesh (East)", "Idea Cellular Limited"),
            new SPNObject(404, 90, "Airtel - Maharashtra", "Bharti Airtel"),
            new SPNObject(404, 91, "Airtel - Kolkata Metro Circle", "Bharti Airtel"),
            new SPNObject(404, 92, "Airtel Mumbai", "Bharti Airtel"),
            new SPNObject(404, 93, "Airtel Madhya Pradesh", "Bharti Airtel"),
            new SPNObject(404, 94, "Airtel Tamilnadu", "Bharti Airtel"),
            new SPNObject(404, 95, "Airtel - Kerala", "Bharti Airtel"),
            new SPNObject(404, 96, "Airtel - Haryana", "Bharti Airtel"),
            new SPNObject(404, 97, "Airtel - Uttar Pradesh (West)", "Bharti Airtel"),
            new SPNObject(405, 1, "Reliance - Andhra Pradesh", "Reliance Communications"),
            new SPNObject(405, 3, "Reliance - Bihar", "Reliance Communications"),
            new SPNObject(405, 4, "Reliance - Chennai", "Reliance Communications"),
            new SPNObject(405, 5, "Reliance - Delhi", "Reliance Communications"),
            new SPNObject(405, 6, "Reliance - Gujarat", "Reliance Communications"),
            new SPNObject(405, 7, "Reliance - Haryana", "Reliance Communications"),
            new SPNObject(405, 8, "Reliance - Himachal Pradesh", "Reliance Communications"),
            new SPNObject(405, 9, "Reliance - Jammu & Kashmir", "Reliance Communications"),
            new SPNObject(405, 10, "Reliance - Karnataka", "Reliance Communications"),
            new SPNObject(405, 11, "Reliance - Kerala", "Reliance Communications"),
            new SPNObject(405, 12, "Reliance - Kolkata", "Reliance Communications"),
            new SPNObject(405, 13, "Reliance - Maharashtra", "Reliance Communications"),
            new SPNObject(405, 14, "Reliance - Madhya Pradesh", "Reliance Communications"),
            new SPNObject(405, 15, "Reliance - Mumbai", "Reliance Communications"),
            new SPNObject(405, 17, "Reliance - Orissa", "Reliance Communications"),
            new SPNObject(405, 18, "Reliance - Punjab", "Reliance Communications"),
            new SPNObject(405, 19, "Reliance - Rajasthan", "Reliance Communications"),
            new SPNObject(405, 20, "Reliance - Tamilnadu", "Reliance Communications"),
            new SPNObject(405, 21, "Reliance - Uttar Pradesh (East)", "Reliance Communications"),
            new SPNObject(405, 22, "Reliance - Uttar Pradesh (West)", "Reliance Communications"),
            new SPNObject(405, 23, "Reliance - West Bengal", "Reliance Communications"),
            new SPNObject(405, 23, "Reliance - West Bengal", "Reliance Communications"),
            new SPNObject(405, 25, "Tata - Andhra Pradesh", "Tata Teleservices"),
            new SPNObject(405, 26, "Tata - Assam", "Tata Teleservices"),
            new SPNObject(405, 27, "Tata - Bihar", "Tata Teleservices"),
            new SPNObject(405, 28, "Tata - Chennai", "Tata Teleservices"),
            new SPNObject(405, 29, "Tata - Delhi", "Tata Teleservices"),
            new SPNObject(405, 30, "Tata - Gujarat", "Tata Teleservices"),
            new SPNObject(405, 31, "Tata - Haryana", "Tata Teleservices"),
            new SPNObject(405, 32, "Tata - Himachal Pradesh", "Tata Teleservices"),
            new SPNObject(405, 33, "Tata - Jammu & Kashmir", "Tata Teleservices"),
            new SPNObject(405, 34, "Tata - Karnataka", "Tata Teleservices"),
            new SPNObject(405, 35, "Tata - Kerala", "Tata Teleservices"),
            new SPNObject(405, 36, "Tata - Kolkata", "Tata Teleservices"),
            new SPNObject(405, 37, "Tata - Maharashtra", "Tata Teleservices"),
            new SPNObject(405, 38, "Tata - Madhya Pradesh", "Tata Teleservices"),
            new SPNObject(405, 39, "Tata - Mumbai", "Tata Teleservices"),
            new SPNObject(405, 40, "Tata - North East", "Tata Teleservices"),
            new SPNObject(405, 41, "Tata - Orissa", "Tata Teleservices"),
            new SPNObject(405, 42, "Tata - Punjab", "Tata Teleservices"),
            new SPNObject(405, 43, "Tata - Rajasthan", "Tata Teleservices"),
            new SPNObject(405, 44, "Tata - Tamilnadu", "Tata Teleservices"),
            new SPNObject(405, 45, "Tata - Uttar Pradesh (East)", "Tata Teleservices"),
            new SPNObject(405, 46, "Tata - Uttar Pradesh (West)", "Tata Teleservices"),
            new SPNObject(405, 47, "Tata - West Bengal", "Tata Teleservices"),
            new SPNObject(405, 51, "Airtel - West Bengal", "Bharti Airtel"),
            new SPNObject(405, 52, "Airtel - Bihar", "Bharti Airtel"),
            new SPNObject(405, 53, "Airtel - Orissa", "Bharti Airtel"),
            new SPNObject(405, 54, "Airtel - Uttar Pradesh (East)", "Bharti Airtel"),
            new SPNObject(405, 55, "Airtel - Jammu & Kashmir", "Bharti Airtel"),
            new SPNObject(405, 56, "Airtel - Assam", "Bharti Airtel"),
            new SPNObject(405, 66, "Vodafone - Uttar Pradesh (West)", "Vodafone"),
            new SPNObject(405, 67, "Vodafone - West Bengal", "Vodafone"),
            new SPNObject(405, 70, "Idea - Bihar", "Idea Cellular Limited"),
            new SPNObject(405, 750, "Vodafone - Jammu & Kashmir", "Vodafone"),
            new SPNObject(405, 751, "Vodafone - Assam", "Vodafone"),
            new SPNObject(405, 752, "Vodafone - Bihar", "Vodafone"),
            new SPNObject(405, 753, "Vodafone - Orissa", "Vodafone"),
            new SPNObject(405, 754, "Vodafone - Himachal Pradesh", "Vodafone"),
            new SPNObject(405, 755, "Vodafone - North East", "Vodafone"),
            new SPNObject(405, 756, "Vodafone - Madhya Pradesh", "Vodafone"),
            new SPNObject(405, 799, "Idea - Mumbai", "Idea Cellular Limited"),
            new SPNObject(405, 800, "Aircel - Delhi", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 801, "Aircel - Andhra Pradesh", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 802, "Aircel - Gujarat", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 803, "Aircel - Karnataka", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 804, "Aircel - Maharashtra", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 805, "Aircel - Mumbai", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 806, "Aircel - Rajasthan", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 807, "Aircel - Haryana", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 808, "Aircel - Punjab", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 809, "Aircel - Kerala", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 810, "Aircel - Uttar Pradesh (East)", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 811, "Aircel - Uttar Pradesh (West)", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 812, "Aircel - Madhya Pradesh", "Dishnet Wireless/Aircel"),
            new SPNObject(405, 813, "Unitech - Haryana", "Unitech Wireless"),
            new SPNObject(405, 814, "Unitech - Himachal Pradesh", "Unitech Wireless"),
            new SPNObject(405, 815, "Unitech - Jammu & Kashmir", "Unitech Wireless"),
            new SPNObject(405, 816, "Unitech - Punjab", "Unitech Wireless"),
            new SPNObject(405, 817, "Unitech - Rajasthan", "Unitech Wireless"),
            new SPNObject(405, 818, "Unitech - Uttar Pradesh (West)", "Unitech Wireless"),
            new SPNObject(405, 819, "Unitech - Andhra Pradesh", "Unitech Wireless"),
            new SPNObject(405, 820, "Unitech - Karnataka", "Unitech Wireless"),
            new SPNObject(405, 821, "Unitech - Kerala", "Unitech Wireless"),
            new SPNObject(405, 822, "Unitech - Kolkata", "Unitech Wireless"),
            new SPNObject(405, 844, "Unitech - Delhi", "Unitech Wireless"),
            new SPNObject(405, 845, "Idea - Assam", "Idea Cellular Limited"),
            new SPNObject(405, 846, "Idea - Jammu & Kashmir", "Idea Cellular Limited"),
            new SPNObject(405, 847, "Idea - Karnataka", "Idea Cellular Limited"),
            new SPNObject(405, 848, "Idea - Kolkata", "Idea Cellular Limited"),
            new SPNObject(405, 849, "Idea - North East", "Idea Cellular Limited"),
            new SPNObject(405, 850, "Idea - Orissa", "Idea Cellular Limited"),
            new SPNObject(405, 851, "Idea - Punjab", "Idea Cellular Limited"),
            new SPNObject(405, 852, "Idea - Tamilnadu", "Idea Cellular Limited"),
            new SPNObject(405, 853, "Idea - West Bengal", "Idea Cellular Limited"),
            new SPNObject(405, 854, "Loop - Andhra Pradesh", "Loop Mobile"),
            new SPNObject(405, 875, "Unitech - Assam", "Unitech Wireless"),
            new SPNObject(405, 876, "Unitech - Bihar", "Unitech Wireless"),
            new SPNObject(405, 877, "Unitech - North East", "Unitech Wireless"),
            new SPNObject(405, 878, "Unitech - Orissa", "Unitech Wireless"),
            new SPNObject(405, 879, "Unitech - Uttar Pradesh (East)", "Unitech Wireless"),
            new SPNObject(405, 880, "Unitech - West Bengal", "Unitech Wireless"),
            new SPNObject(405, 887, "Shyam - Andhra Pradesh", "Sistema Shyam"),
            new SPNObject(405, 888, "Shyam - Assam", "Sistema Shyam"),
            new SPNObject(405, 889, "Shyam - Bihar", "Sistema Shyam"),
            new SPNObject(405, 890, "Shyam - Delhi", "Sistema Shyam"),
            new SPNObject(405, 891, "Shyam - Gujarat", "Sistema Shyam"),
            new SPNObject(405, 892, "Shyam - Haryana", "Sistema Shyam"),
            new SPNObject(405, 893, "Shyam - Himachal Pradesh", "Sistema Shyam"),
            new SPNObject(405, 894, "Shyam - Jammu & Kashmir", "Sistema Shyam"),
            new SPNObject(405, 895, "Shyam - Karnataka", "Sistema Shyam"),
            new SPNObject(405, 896, "Shyam - Kerala", "Sistema Shyam"),
            new SPNObject(405, 897, "Shyam - Kolkata", "Sistema Shyam"),
            new SPNObject(405, 898, "Shyam - Maharashtra", "Sistema Shyam"),
            new SPNObject(405, 899, "Shyam - Madhya Pradesh", "Sistema Shyam"),
            new SPNObject(405, 900, "Shyam - Mumbai", "Sistema Shyam"),
            new SPNObject(405, 901, "Shyam - North East", "Sistema Shyam"),
            new SPNObject(405, 902, "Shyam - Orissa", "Sistema Shyam"),
            new SPNObject(405, 912, "Etisalat  - Andhra Pradesh", "Etisalat DB"),
            /******************
             **** Pakistan ****
             ******************/
            new SPNObject(410, 1, "Mobilink", "Mobilink-PMCL"),
            new SPNObject(410, 3, "Ufone", "Pakistan Telecommunication Mobile Ltd"),
            new SPNObject(410, 4, "Zong", "China Mobile"),
            new SPNObject(410, 6, "Telenor", "Telenor Pakistan"),
            new SPNObject(410, 7, "Warid", "WaridTel"),

            /*******************
             *** Afghanistan ***
             ********************/
            new SPNObject(412, 1, "AWCC", "Afghan wireless Communication Company"),
            new SPNObject(412, 20, "Roshan", "Telecom Development Company Afghanistan Ltd."),
            new SPNObject(412, 40, "Areeba", "MTN Afghanistan"),
            new SPNObject(412, 50, "Etisalat", "Etisalat Afghanistan"),

            /*******************
             **** Sri Lanka ****
             *******************/
            new SPNObject(413, 1, "Mobitel", "Mobitel Lanka Ltd."),
            new SPNObject(413, 2, "Dialog", "Dialog Telekom PLC."),
            new SPNObject(413, 3, "Tigo", "Celtel Lanka Ltd"),
            new SPNObject(413, 8, "Hutch Sri Lanka", "Hutch Sri Lanka"),

            /*****************
             **** Myanmar ****
             *****************/
            new SPNObject(414, 1, "MPT", "Myanmar Post and Telecommunication"),

            /*****************
             **** Lebanon ****
             *****************/
            new SPNObject(415, 1, "Alfa", "Alfa"),
            new SPNObject(415, 3, "MTC-Touch", "MIC 2"),

            /****************
             **** Jordan ****
             ****************/
            new SPNObject(416, 1, "Zain", "Jordan Mobile Teelphone Services"),
            new SPNObject(416, 3, "Umniah", "Umniah"),
            new SPNObject(416, 77, "Orange",
                    "Oetra Jordanian Mobile Telecommunications Company (MobileCom)"),

            /***************
             **** Syria ****
             ***************/
            new SPNObject(417, 1, "SyriaTel", "SyriaTel"),
            new SPNObject(417, 2, "MTN Syria", "MTN Syria (JSC)"),

            /****************
             **** Iraq ****
             ****************/
            new SPNObject(418, 20, "Zain Iraq", "Zain Iraq"),
            new SPNObject(418, 30, "Zain Iraq", "Zain Iraq"),
            new SPNObject(418, 50, "Asia Cell", "Asia Cell Telecommunications Company"),
            new SPNObject(418, 40, "Korek", "Korel Telecom Ltd"),

            /****************
             **** Kuwait ****
             ****************/
            new SPNObject(419, 2, "Zain", "Mobile Telecommunications Co."),
            new SPNObject(419, 3, "Wataniya", "National Mobile Telecommunications"),
            new SPNObject(419, 4, "Viva", "Kuwait Telecommunication Company"),

            /**********************
             **** Saudi Arabia ****
             **********************/
            new SPNObject(420, 1, "STC", "Saudi Telecom Company"),
            new SPNObject(420, 3, "Mobily", "Etihad Etisalat Company"),
            new SPNObject(420, 4, "Zain SA", "MTC Saudi Arabia"),

            /***************
             **** Yemen ****
             ***************/
            new SPNObject(421, 1, "SabaFon", "SabaFon"),
            new SPNObject(421, 2, "MTN", "Spacetel"),

            /**************
             **** Oman ****
             **************/
            new SPNObject(422, 2, "Oman Mobile", "Oman Telecommunications Company"),
            new SPNObject(422, 3, "Nawras", "Omani Qatari Telecommunications Company SAOC"),

            /******************************
             **** United Arab Emirates ****
             ******************************/
            new SPNObject(424, 2, "Etisalat", "Emirates Telecom Corp"),
            new SPNObject(424, 3, "du", "Emirates Integrated Telecommunications Company"),

            /****************
             **** Israel ****
             ****************/
            new SPNObject(425, 1, "Orange", "Partner Communications Company Ltd"),
            new SPNObject(425, 2, "Cellcom", "Cellcom"),
            new SPNObject(425, 3, "Pelephone", "Pelephone"),

            /*******************************
             **** Palestinian Authority ****
             *******************************/
            new SPNObject(425, 5, "JAWWAL", "Palestine Cellular Communications, Ltd."),

            /***************
             **** Qatar ****
             ***************/
            new SPNObject(427, 1, "Qatarnet", "Q-Tel"),

            /******************
             **** Mongolia ****
             ******************/
            new SPNObject(428, 88, "Unitel", "Unitel LLC"),
            new SPNObject(428, 99, "MobiCom", "MobiCom Corporation"),

            /***************
             **** Nepal ****
             ***************/
            new SPNObject(429, 1, "Nepal Telecom", "Nepal Telecom"),
            new SPNObject(429, 2, "Mero Mobile", "Spice Nepal Private Ltd"),

            /**************
             **** Iran ****
             **************/
            new SPNObject(432, 11, "MCI", "Mobile Communications Company of Iran"),
            new SPNObject(432, 14, "TKC", "KFZO"),
            new SPNObject(432, 19, "MTCE", "Mobile Telecommunications Company of Esfahan"),
            new SPNObject(432, 32, "Taliya", "Taliya"),
            new SPNObject(432, 35, "Irancell", "Irancell Telecommunications Services Company"),

            /********************
             **** Uzbekistan ****
             ********************/
            new SPNObject(434, 4, "Beeline", "Unitel LLC"),
            new SPNObject(434, 5, "Ucell", "Coscom"),
            new SPNObject(434, 7, "MTS", "Mobile teleSystems (FE 'Uzdunrobita' Ltd)"),

            /********************
             **** Tajikistan ****
             ********************/
            new SPNObject(436, 1, "Somoncom", "JV Somoncom"),
            new SPNObject(436, 2, "Indigo", "Indigo Tajikistan"),
            new SPNObject(436, 3, "MLT", "TT Mobile, Closed joint-stock company"),
            new SPNObject(436, 4, "Babilon-M", "CJSC Babilon-Mobile"),
            new SPNObject(436, 5, "Beeline TJ", "Co Ltd. Tacom"),

            /********************
             **** Kyrgyzstan ****
             ********************/
            new SPNObject(437, 1, "Bitel", "Sky Mobile LLC"),
            new SPNObject(437, 5, "MegaCom", "BiMoCom Ltd"),
            new SPNObject(437, 9, "O!", "NurTelecom LLC"),

            /**********************
             **** Turkmenistan ****
             **********************/
            new SPNObject(438, 1, "MTS", "Barash Communication Technologies"),
            new SPNObject(438, 2, "TM-Cell", "TM-Cell"),

            /***************
             **** Japan ****
             ***************/
            new SPNObject(440, 0, "eMobile", "eMobile, Ltd."),
            new SPNObject(440, 1, "DoCoMo", "NTT DoCoMo"),
            new SPNObject(440, 2, "DoCoMo", "NTT DoCoMo Kansai"),
            new SPNObject(440, 3, "DoCoMo", "NTT DoCoMo Hokuriku"),
            new SPNObject(440, 4, "SoftBank", "SoftBank Mobile Corp"),
            new SPNObject(440, 6, "SoftBank", "SoftBank Mobile Corp"),
            new SPNObject(440, 10, "DoCoMo", "NTT DoCoMo Kansai"),
            new SPNObject(440, 20, "SoftBank", "SoftBank Mobile Corp"),

            /*********************
             **** South Korea ****
             *********************/
            new SPNObject(450, 5, "SKT", "SK Telecom"),
            new SPNObject(450, 8, "KTF SHOW", "KTF"),

            /*****************
             **** Vietnam ****
             *****************/
            new SPNObject(452, 1, "MobiFone", "Vietnam Mobile Telecom Services Company"),
            new SPNObject(452, 2, "Vinaphone", "Vietnam Telecoms Services Company"),
            new SPNObject(452, 4, "Viettel Mobile", "iViettel Corporation"),

            /*******************
             **** Hong Kong ****
             *******************/
            new SPNObject(454, 0, "CSL", "Hong Kong CSL Limited"),
            new SPNObject(454, 1, "CITIC Telecom 1616", "CITIC Telecom 1616"),
            new SPNObject(454, 2, "CSL 3G", "Hong Kong CSL Limited"),
            new SPNObject(454, 3, "3(3G)", "Hutchison Telecom"),
            new SPNObject(454, 4, "3 DualBand (2G)", "Hutchison Telecom"),
            new SPNObject(454, 6, "Smartone-Vodafone", "SmarTone Mobile Comms"),
            new SPNObject(454, 7, "China Unicom", "China Unicom"),
            new SPNObject(454, 8, "Trident", "Trident"),
            new SPNObject(454, 9, "China Motion Telecom", "China Motion Telecom"),
            new SPNObject(454, 10, "New World", "Hong Kong CSL Limited"),
            new SPNObject(454, 11, "Chia-HongKong Telecom", "Chia-HongKong Telecom"),
            new SPNObject(454, 12, "CMCC Peoples", "China Mobile Hong Kong Company Limited"),
            new SPNObject(454, 14, "Hutchison Telecom", "Hutchison Telecom"),
            new SPNObject(454, 15, "SmarTone Mobile Comms", "SmarTone Mobile Comms"),
            new SPNObject(454, 16, "PCCW", "PCCW Mobile (PCCW Ltd)"),
            new SPNObject(454, 17, "SmarTone Mobile Comms", "SmarTone Mobile Comms"),
            new SPNObject(454, 18, "Hong Kong CSL Limited", "Hong Kong CSL Limited"),
            new SPNObject(454, 19, "PCCW", "PCCW Mobile (PCCW Ltd)"),

            /***************
             **** Macau ****
             ***************/
            new SPNObject(455, 0, "SmarTone", "SmarTone Macau"),
            new SPNObject(455, 1, "CTM", "C.T.M. Telemovel+"),
            new SPNObject(455, 3, "3", "Hutchison Telecom"),
            new SPNObject(455, 4, "CTM", "C.T.M. Telemovel+"),
            new SPNObject(455, 5, "3", "Hutchison Telecom"),

            /******************
             **** Cambodia ****
             ******************/
            new SPNObject(456, 1, "Mobitel", "CamGSM"),
            new SPNObject(456, 2, "hello", "Telekom Malaysia International (Cambodia) Co. Ltd"),
            new SPNObject(456, 4, "qb", "Cambodia Advance Communications Co. Ltd"),
            new SPNObject(456, 5, "Star-Cell", "APPLIFONE CO. LTD."),
            new SPNObject(456, 18, "Shinawatra", "Shinawatra"),

            /**************
             **** Laos ****
             **************/
            new SPNObject(457, 1, "LaoTel", "Lao Shinawatra Telecom"),
            new SPNObject(457, 2, "ETL", "Enterprise of Telecommunications Lao"),
            new SPNObject(457, 3, "LAT", "Lao Asia Telecommunication State Enterprise (LAT)"),
            new SPNObject(457, 8, "Tigo", "Millicom Lao Co Ltd"),

            /***************
             **** China ****
             ***************/
            new SPNObject(460, 0, "China Mobile", "China Mobile"),
            new SPNObject(460, 2, "China Mobile", "China Mobile"),
            new SPNObject(460, 7, "China Mobile", "China Mobile"),
            new SPNObject(460, 1, "China Unicom", "China Unicom"),
            new SPNObject(460, 6, "China Unicom", "China Unicom"),
            new SPNObject(460, 3, "China Telecom", "China Telecom"),
            new SPNObject(460, 5, "China Telecom", "China Telecom"),
            /****************
             **** Taiwan ****
             ****************/
            new SPNObject(466, 1, "FarEasTone", "Far EasTone Telecommunications Co Ltd"),
            new SPNObject(466, 6, "Tuntex", "Tuntex Telecom"),
            new SPNObject(466, 88, "KG Telecom", "KG Telecom"),
            new SPNObject(466, 89, "VIBO", "VIBO Telecom"),
            new SPNObject(466, 92, "Chungwa", "Chungwa"),
            new SPNObject(466, 93, "MobiTai", "iMobitai Communications"),
            new SPNObject(466, 97, "Taiwan Mobile", "Taiwan Mobile Co. Ltd"),
            new SPNObject(466, 99, "TransAsia", "TransAsia Telecoms"),

            /*********************
             **** North Korea ****
             *********************/
            new SPNObject(467, 193, "SUN NET", "Korea Posts and Telecommunications Corporation"),

            /********************
             **** Bangladesh ****
             ********************/
            new SPNObject(470, 1, "Grameenphone", "GrameenPhone Ltd"),
            new SPNObject(470, 2, "Aktel", "Aktel"),
            new SPNObject(470, 3, "Banglalink", "Orascom telecom Bangladesh Limited"),
            new SPNObject(470, 4, "TeleTalk", "TeleTalk"),
            new SPNObject(470, 6, "Citycell", "Citycell"),
            new SPNObject(470, 7, "Warid", "Warid Telecom"),

            /******************
             **** Maldives ****
             ******************/
            new SPNObject(472, 1, "Dhiraagu", "Dhivehi Raajjeyge Gulhun"),
            new SPNObject(472, 2, "Wataniya", "Wataniya Telecom Maldives"),

            /******************
             **** Malaysia ****
             ******************/
            new SPNObject(502, 12, "Maxis", "Maxis Communications Berhad"),
            new SPNObject(502, 13, "Celcom", "Celcom Malaysia Sdn Bhd"),
            new SPNObject(502, 16, "DiGi", "DiGi Telecommunications"),
            new SPNObject(502, 18, "U Mobile", "U Mobile Sdn Bhd"),
            new SPNObject(502, 19, "Celcom", "Celcom Malaysia Sdn Bhd"),

            /*******************
             **** Australia ****
             *******************/
            new SPNObject(505, 1, "Telstra", "Telstra Corp. Ltd."),
            new SPNObject(505, 2, "YES OPTUS", "Singtel Optus Ltd"),
            new SPNObject(505, 3, "Vodafone", "Vodafone Australia"),
            new SPNObject(505, 6, "3", "Hutchison 3G"),
            new SPNObject(505, 90, "YES OPTUS", "Singtel Optus Ltd"),

            /*******************
             **** Indonesia ****
             *******************/
            new SPNObject(510, 0, "PSN", "PT Pasifik Satelit Nusantara (ACeS)"),
            new SPNObject(510, 1, "INDOSAT", "PT Indonesian Satellite Corporation Tbk (INDOSAT)"),
            new SPNObject(510, 8, "AXIS", "PT Natrindo Telepon Seluler"),
            new SPNObject(510, 10, "Telkomsel", "PT Telkomunikasi Selular"),
            new SPNObject(510, 11, "XL", "PT Excelcomindo Pratama"),
            new SPNObject(510, 89, "3", "PT Hutchison CP Telecommunications"),

            /********************
             **** East Timor ****
             ********************/
            new SPNObject(514, 2, "Timor Telecom", "Timor Telecom"),

            /********************
             **** Philipines ****
             ********************/
            new SPNObject(515, 1, "Islacom", "Innove Communicatiobs Inc"),
            new SPNObject(515, 2, "Globe", "Globe Telecom"),
            new SPNObject(515, 3, "Smart Gold", "Smart Communications Inc"),
            new SPNObject(515, 5, "Digitel", "Digital Telecommunications Philppines"),
            new SPNObject(515, 18, "Red Mobile", "Connectivity Unlimited resource Enterprise"),

            /******************
             **** Thailand ****
             ******************/
            new SPNObject(520, 1, "Advanced Info Service", "Advanced Info Service"),
            new SPNObject(520, 15, "ACT Mobile", "ACT Mobile"),
            new SPNObject(520, 18, "DTAC", "Total Access Communication"),
            new SPNObject(520, 23, "Advanced Info Service", "Advanced Info Service"),
            new SPNObject(520, 99, "True Move", "True Move"),

            /*******************
             **** Singapore ****
             *******************/
            new SPNObject(525, 1, "SingTel", "Singapore Telecom"),
            new SPNObject(525, 2, "SingTel-G18", "Singapore Telecom"),
            new SPNObject(525, 3, "M1", "MobileOne Asia"),
            new SPNObject(525, 5, "StarHub", "StarHub Mobile"),

            /****************
             **** Brunei ****
             ****************/
            new SPNObject(528, 2, "B-Mobile", "B-Mobile Communications Sdn Bhd"),
            new SPNObject(528, 11, "DTSCom", "DataStream Technology"),

            /*********************
             **** New Zealand ****
             *********************/
            new SPNObject(530, 1, "Vodafone", "Vodafone New Zealand"),
            new SPNObject(530, 3, "Woosh", "Woosh wireless New Zealand"),
            new SPNObject(530, 5, "Telecom", "Telecom New Zealand"),
            new SPNObject(530, 24, "NZ Comms", "NZ Communications New Zealand"),

            /**************************
             **** Papua New Guinea ****
             **************************/
            new SPNObject(537, 1, "B-Mobile", "Pacific Mobile Communications"),

            /*************************
             **** Solomon Islands ****
             *************************/
            new SPNObject(540, 1, "BREEZE", "Solomon Telekom Co Ltd"),

            /*****************
             **** Vanuatu ****
             *****************/
            new SPNObject(541, 1, "SMILE", "telecom Vanuatu Ltd"),

            /**************
             **** Fiji ****
             **************/
            new SPNObject(542, 1, "Vodafone", "Vodafone Fiji"),

            /******************
             **** Kiribati ****
             ******************/
            new SPNObject(545, 9, "Kiribati Frigate", "Telecom services Kiribati Ltd"),

            /***********************
             **** New Caledonia ****
             ***********************/
            new SPNObject(546, 1, "Mobilis", "OPT New Caledonia"),

            /**************************
             **** French Polynesia ****
             **************************/
            new SPNObject(547, 20, "VINI", "Tikiphone SA"),

            /************************************
             **** Cook Islands (New Zealand) ****
             ************************************/
            new SPNObject(548, 1, "Telecom Cook", "Telecom Cook"),

            /***************
             **** Samoa ****
             ***************/
            new SPNObject(549, 1, "Digicel", "Digicel Pacific Ltd."),
            new SPNObject(549, 27, "SamoaTel", "SamoaTel Ltd"),

            /********************
             **** Micronesia ****
             ********************/
            new SPNObject(550, 1, "FSM Telecom", "FSM Telecom"),

            /***************
             **** Palau ****
             ***************/
            new SPNObject(552, 1, "PNCC", "Palau National Communications Corp."),
            new SPNObject(552, 80, "Palau Mobile", "Palau Mobile Corporation"),

            /***************
             **** Egypt ****
             ***************/
            new SPNObject(602, 1, "Mobinil", "ECMS-Mobinil"),
            new SPNObject(602, 2, "Vodafone", "Vodafone Egypt"),
            new SPNObject(602, 3, "etisalat", "Etisalat Egypt"),

            /*****************
             **** Algeria ****
             *****************/
            new SPNObject(603, 1, "Mobilis", "ATM Mobilis"),
            new SPNObject(603, 2, "Djezzy", "Orascom Telecom Algerie Spa"),
            new SPNObject(603, 3, "Nedjma", "Wataniya Telecom Algerie"),

            /*****************
             **** Morocco ****
             *****************/
            new SPNObject(604, 0, "Meditel", "Medi Telecom"),
            new SPNObject(604, 1, "IAM", "Ittissalat Al Maghrib (Maroc Telecom)"),

            /*****************
             **** Tunisia ****
             *****************/
            new SPNObject(605, 2, "Tunicell", "Tunisie Telecom"),
            new SPNObject(605, 3, "Tunisiana", "Orascom Telecom Tunisie"),

            /***************
             **** Libya ****
             ***************/
            new SPNObject(606, 0, "Libyana", "Libyana"),
            new SPNObject(606, 1, "Madar", "Al Madar"),

            /*******************
             **** Mauritius ****
             *******************/
            new SPNObject(609, 1, "Mattel", "Mattel"),
            new SPNObject(609, 10, "Mauritel", "Mauritel Mobiles"),

            /**************
             **** Mali ****
             **************/
            new SPNObject(610, 1, "Malitel", "Malitel SA"),
            new SPNObject(610, 2, "Orange", "Orange Mali SA"),

            /****************
             **** Guinea ****
             ****************/
            new SPNObject(611, 2, "Lagui", "Sotelgui Lagui"),
            new SPNObject(611, 3, "Telecel Guinee", "INTERCEL Guinee"),
            new SPNObject(611, 4, "MTN", "Areeba Guinea"),

            /*********************
             **** Ivory Coast ****
             *********************/
            new SPNObject(612, 2, "Moov", "Moov"),
            new SPNObject(612, 3, "Orange", "Orange"),
            new SPNObject(612, 4, "KoZ", "Comium Ivory Coast Inc"),
            new SPNObject(612, 5, "MTN", "MTN"),
            new SPNObject(612, 6, "ORICEL", "ORICEL"),

            /**********************
             **** Burkina Faso ****
             **********************/
            new SPNObject(613, 1, "Onatel", "Onatel"),
            new SPNObject(613, 2, "Zain", "Celtel Burkina Faso"),
            new SPNObject(613, 3, "Telecel Faso", "Telecel Faso SA"),

            /*****************
             **** Nigeria ****
             *****************/
            new SPNObject(614, 1, "SahelCom", "SahelCom"),
            new SPNObject(614, 2, "Zain", "Celtel Niger"),
            new SPNObject(614, 3, "Telecel", "Telecel Niger SA"),
            new SPNObject(614, 4, "Orange", "Orange Niger"),

            /**************
             **** Togo ****
             **************/
            new SPNObject(615, 1, "Togo Cell", "Togo Telecom"),
            new SPNObject(615, 5, "Telecel", "Telecel Togo"),

            /***************
             **** Benin ****
             ***************/
            new SPNObject(616, 0, "BBCOM", "Bell Benin Communications"),
            new SPNObject(616, 2, "Telecel", "Telecel Benin Ltd"),
            new SPNObject(616, 3, "Areeba", "Spacetel Benin"),

            /*******************
             **** Mauritius ****
             *******************/
            new SPNObject(617, 1, "Orange", "Cellplus Mobile Communications Ltd"),
            new SPNObject(617, 10, "Emtel", "Emtel Ltd"),

            /*****************
             **** Liberia ****
             *****************/
            new SPNObject(618, 1, "LoneStar Cell", "Lonestar Communications Corporation"),

            /***************
             **** Ghana ****
             ***************/
            new SPNObject(620, 1, "MTN", "ScanCom Ltd"),
            new SPNObject(620, 2, "Ghana Telecomi Mobile", "Ghana Telecommunications Company Ltd"),
            new SPNObject(620, 3, "tiGO", "Millicom Ghana Limited"),

            /*****************
             **** Nigeria ****
             *****************/
            new SPNObject(621, 20, "Zain", "Celtel Nigeria Ltd."),
            new SPNObject(621, 30, "MTN", "MTN Nigeria Communications Limited"),
            new SPNObject(621, 40, "M-Tel", "Nigerian Mobile Telecommunications Limited"),
            new SPNObject(621, 50, "Glo", "Globacom Ltd"),

            /**************
             **** Chad ****
             **************/
            new SPNObject(622, 1, "Zain", "CelTel Tchad SA"),
            new SPNObject(622, 3, "TIGO - Millicom", "TIGO - Millicom"),

            /**********************************
             **** Central African Republic ****
             **********************************/
            new SPNObject(623, 1, "CTP", "Centrafrique Telecom Plus"),
            new SPNObject(623, 2, "TC", "iTelecel Centrafrique"),
            new SPNObject(623, 3, "Orange", "Orange RCA"),
            new SPNObject(623, 4, "Nationlink", "Nationlink Telecom RCA"),

            /******************
             **** Cameroon ****
             ******************/
            new SPNObject(624, 1, "MTN-Cameroon", "Mobile Telephone Network Cameroon Ltd"),
            new SPNObject(624, 2, "Orange", "Orange Cameroun S.A."),

            /********************
             **** Cabo Verde ****
             ********************/
            new SPNObject(625, 1, "CMOVEL", "CVMovel, S.A."),

            /*******************************
             **** Sao Tome and Principe ****
             *******************************/
            new SPNObject(626, 1, "CSTmovel", "Companhia Santomese de Telecomunicacoe"),

            /**************************
             *** Equatorial Guinea ****
             **************************/
            new SPNObject(627, 1, "Orange GQ", "GETESA"),

            /***************
             **** Gabon ****
             ***************/
            new SPNObject(628, 1, "Libertis", "Libertis S.A."),
            new SPNObject(628, 2, "Moov (Telecel) Gabon S.A.", "Moov (Telecel) Gabon S.A."),
            new SPNObject(628, 3, "Zain", "Celtel Gabon S.A."),

            /*******************************
             **** Republic of the Congo ****
             *******************************/
            new SPNObject(629, 10, "Libertis Telecom", "MTN CONGO S.A"),

            /******************************************
             **** Democratic Republic of the Congo ****
             ******************************************/
            new SPNObject(630, 1, "Vodacom", "Vodacom Congo RDC sprl"),
            new SPNObject(630, 2, "Zain", "Celtel Congo"),
            new SPNObject(630, 5, "Supercell", "Supercell SPRL"),
            new SPNObject(630, 86, "CCT", "Congo-Chine Telecom s.a.r.l"),
            new SPNObject(630, 89, "SAIT Telecom", "OASIS SPRL"),

            /*****************
             **** Angola ****
             *****************/
            new SPNObject(631, 2, "UNITEL", "UNITEL S.a.r.l."),

            /***********************
             **** Guinea-Bissau ****
             ***********************/
            new SPNObject(632, 2, "Areeba", "Spacetel Guine-Bissau S.A."),

            /********************
             **** Seychelles ****
             ********************/
            new SPNObject(633, 2, "Mdeiatech International", "Mdeiatech International Ltd."),

            /***************
             **** Sudan ****
             ***************/
            new SPNObject(634, 1, "Mobitel/Mobile Telephone Company",
                    "Mobitel/Mobile Telephone Company"),
            new SPNObject(634, 2, "MTN", "MTN Sudan"),

            /****************
             **** Rwanda ****
             ****************/
            new SPNObject(635, 10, "MTN", "MTN Rwandacell SARL"),

            /******************
             **** Ethiopia ****
             ******************/
            new SPNObject(636, 1, "ETMTN", "Ethiopian Telecommmunications Corporation"),

            /*****************
             **** Somalia ****
             *****************/
            new SPNObject(637, 4, "Somafona", "Somafona FZLLC"),
            new SPNObject(637, 10, "Nationalink", "Nationalink"),
            new SPNObject(637, 19, "Hormuud", "Hormuud Telecom Somalia Inc"),
            new SPNObject(637, 30, "Golis", "Golis Telecommunications Company"),
            new SPNObject(637, 62, "Telcom Mobile", "Telcom Mobile"),
            new SPNObject(637, 65, "Telcom Mobile", "Telcom Mobile"),
            new SPNObject(637, 82, "Telcom Somalia", "Telcom Somalia"),

            /******************
             **** Djibouti ****
             ******************/
            new SPNObject(638, 1, "Evatis", "Djibouti Telecom SA"),

            /***************
             **** Kenya ****
             ***************/
            new SPNObject(639, 2, "Safaricom", "Safaricom Limited"),
            new SPNObject(639, 3, "Zain", "Celtel Kenya Limited"),
            new SPNObject(639, 7, "Orange Kenya", "Telkom Kemya"),

            /******************
             **** Tanzania ****
             ******************/
            new SPNObject(640, 2, "Mobitel", "MIC Tanzania Limited"),
            new SPNObject(640, 3, "Zantel", "Zanzibar Telecom Ltd"),
            new SPNObject(640, 4, "Vodacom", "Vodacom Tanzania Limited"),

            /****************
             **** Uganda ****
             ****************/
            new SPNObject(641, 10, "MTN", "MTN Uganda"),
            new SPNObject(641, 14, "Orange", "Orange Uganda"),
            new SPNObject(641, 22, "Warid Telecom", "Warid Telecom"),

            /*****************
             **** Burundi ****
             *****************/
            new SPNObject(642, 1, "Spacetel", "Econet Wireless Burundi PLC"),
            new SPNObject(642, 2, "Aficell", "Africell PLC"),
            new SPNObject(642, 3, "Telecel", "Telecel Burundi Company"),

            /********************
             **** Mozambique ****
             ********************/
            new SPNObject(643, 1, "mCel", "Mocambique Celular S.A.R.L."),

            /****************
             **** Zambia ****
             ****************/
            new SPNObject(645, 1, "Zain", "Zain"),
            new SPNObject(645, 2, "MTN", "MTN"),
            new SPNObject(645, 3, "ZAMTEL", "ZAMTEL"),

            /********************
             **** Madagascar ****
             ********************/
            new SPNObject(646, 1, "Zain", "Celtel"),
            new SPNObject(646, 2, "Orange", "Orange Madagascar S.A."),
            new SPNObject(646, 4, "Telma", "Telma Mobile S.A."),

            /**************************
             **** Reunion (France) ****
             **************************/
            new SPNObject(647, 0, "Orange", "Orange La Reunion"),
            new SPNObject(647, 2, "Outremer", "Outremer Telecom"),
            new SPNObject(647, 10, "SFR Reunion", "Societe Reunionnaisei de Radiotelephone"),

            /******************
             **** Zimbabwe ****
             ******************/
            new SPNObject(648, 1, "Net*One", "Net*One cellular (Pvt) Ltd"),
            new SPNObject(648, 3, "Telecel", "Telecel Zimbabwe (PVT) Ltd"),
            new SPNObject(648, 4, "Econet", "Econet Wireless (Private) Limited"),

            /*****************
             **** Namibia ****
             *****************/
            new SPNObject(649, 1, "MTC", "MTC Namibia"),
            new SPNObject(649, 3, "Cell One", "Telecel Globe (Orascom)"),

            /****************
             **** Malawi ****
             ****************/
            new SPNObject(650, 1, "TNM", "Telecom Network Malawi"),
            new SPNObject(650, 10, "Zain", "Celtel Limited"),

            /*****************
             **** Lesotho ****
             *****************/
            new SPNObject(651, 1, "Vodacom", "Vodacom Lesotho (Pty) Ltd"),

            /******************
             **** Botswana ****
             ******************/
            new SPNObject(652, 1, "Mascom", "Mascom Wirelessi (Pty) Limited"),
            new SPNObject(652, 2, "Orange", "Orange (Botswans) Pty Limited"),
            new SPNObject(652, 4, "BTC Mobile", "Botswana Telecommunications Corporation"),

            /**********************
             **** South Africa ****
             **********************/
            new SPNObject(655, 1, "Vodacom", "Vodacom"),
            new SPNObject(655, 2, "Telkom", "Telkom"),
            new SPNObject(655, 7, "Cell C", "Cell C"),
            new SPNObject(655, 10, "MTN", "MTN Group"),

            /*****************
             **** Eritrea ****
             *****************/
            new SPNObject(657, 1, "Eritel", "Eritel Telecommunications Services Corporation"),

            /****************
             **** Belize ****
             ****************/
            new SPNObject(702, 67, "Belize Telemedia", "Belize Telemedia"),
            new SPNObject(702, 68, "International Telecommunications Ltd.",
                    "International Telecommunications Ltd."),

            /*******************
             **** Guatemala ****
             *******************/
            new SPNObject(704, 1, "Claro",
                    "Servicios de Comunicaciones Personales Inalambricas (SRECOM)"),
            new SPNObject(704, 2, "Comcel / Tigo", "Millicom / Local partners"),
            new SPNObject(704, 3, "movistar", "Telefonica Moviles Guatemala (Telefonica)"),

            /*********************
             **** El Salvador ****
             *********************/
            new SPNObject(706, 1, "CTE Telecom Personal", "CTE Telecom Personal SA de CV"),
            new SPNObject(706, 2, "digicel", "Digicel Group"),
            new SPNObject(706, 3, "Telemovil EL Salvador", "Telemovil EL Salvador S.A"),
            new SPNObject(706, 4, "movistar", "Telfonica Moviles El Salvador"),
            new SPNObject(706, 10, "Claro", "America Movil"),

            /******************
             **** Honduras ****
             ******************/
            new SPNObject(708, 1, "Claro", "Servicios de Comunicaciones de Honduras S.A. de C.V."),
            new SPNObject(708, 2, "Celtel / Tigo", "Celtel / Tigo"),
            new SPNObject(708, 4, "DIGICEL", "Digicel de Honduras"),
            new SPNObject(708, 30, "Hondutel", "Empresa Hondurena de telecomunicaciones"),

            /*******************
             **** Nicaragua ****
             *******************/
            new SPNObject(710, 21, "Claro", "Empresa Nicaraguense de Telecomunicaciones,S.A."),
            new SPNObject(710, 30, "movistar", "Telefonica Moviles de Nicaragua S.A."),
            new SPNObject(710, 73, "SERCOM", "Servicios de Comunicaciones S.A."),

            /*******************
             **** Cost Rica ****
             *******************/
            new SPNObject(712, 1, "ICE", "Instituto Costarricense de Electricidad"),
            new SPNObject(712, 2, "ICE", "Instituto Costarricense de Electricidad"),

            /****************
             **** Panama ****
             ****************/
            new SPNObject(714, 1, "Cable & Wireless", "Cable & Wireless Panama S.A."),
            new SPNObject(714, 2, "movistar", "Telefonica Moviles Panama S.A"),
            new SPNObject(714, 4, "Digicel", "Digicel (Panama) S.A."),

            /**************
             **** Peru ****
             **************/
            new SPNObject(716, 6, "movistar", "Telefonica Moviles Peru"),
            new SPNObject(716, 10, "Claro", "America Movil Peru"),

            /*******************
             **** Argentina ****
             *******************/
            new SPNObject(722, 10, "Movistar", "Telefonica Moviles Argentina SA"),
            new SPNObject(722, 70, "Movistar", "Telefonica Moviles Argentina SA"),
            new SPNObject(722, 310, "Claro", "AMX Argentina S.A"),
            new SPNObject(722, 320, "Claro", "AMX Argentina S.A"),
            new SPNObject(722, 330, "Claro", "AMX Argentina S.A"),
            new SPNObject(722, 340, "Personal", "Teecom Personal SA"),

            /****************
             **** Brazil ****
             ****************/
            new SPNObject(724, 2, "TIM", "Telecom Italia Mobile"),
            new SPNObject(724, 3, "TIM", "Telecom Italia Mobile"),
            new SPNObject(724, 4, "TIM", "Telecom Italia Mobile"),
            new SPNObject(724, 5, "Claro", "Claro (America Movil)"),
            new SPNObject(724, 6, "Vivo", "Vivo S.A."),
            new SPNObject(724, 7, "CTBC Celular", "CTBC Telecom"),
            new SPNObject(724, 8, "TIM", "Telecom Italiz Mobile"),
            new SPNObject(724, 10, "Vivo", "Vivo S.A."),
            new SPNObject(724, 11, "Vivo", "Vivo S.A."),
            new SPNObject(724, 15, "Sercomtel", "Sercomtel Celular"),
            new SPNObject(724, 16, "Oi / Brasil Telecom", "Brasil Telecom Celular SA"),
            new SPNObject(724, 23, "Vivo", "Vivo S.A."),
            new SPNObject(724, 24, "Oi / Amazonia Celular", "Amazonia Celular S.A."),
            new SPNObject(724, 31, "Oi", "TNL PCS"),
            new SPNObject(724, 37, "aeiou", "Unicel do Brasil"),

            /***************
             **** Chile ****
             ***************/
            new SPNObject(730, 1, "Entel", "Entel Pcs"),
            new SPNObject(730, 2, "movistar", "Movistar Chile"),
            new SPNObject(730, 3, "Claro", "Claro Chile"),
            new SPNObject(730, 10, "Entel", "Entel Telefonica Movil"),

            /******************
             **** Colombia ****
             ******************/
            new SPNObject(732, 101, "Comcel", "Comcel Colombia"),
            new SPNObject(732, 102, "movistar", "Bellsouth Colombia"),
            new SPNObject(732, 103, "Tigo", "Colombia Movil"),
            new SPNObject(732, 111, "Tigo", "Colombia Movil"),
            new SPNObject(732, 123, "movistar", "Telefonica Moviles Colombia"),

            /*******************
             **** Venezuela ****
             *******************/
            new SPNObject(734, 1, "Digitel", "Corporacion Digitel C.A."),
            new SPNObject(734, 2, "Digitel", "Corporacion Digitel C.A."),
            new SPNObject(734, 3, "Digitel", "Corporacion Digitel C.A."),
            new SPNObject(734, 4, "movistar", "Telefonica Moviles Venezuela"),
            new SPNObject(734, 6, "Movilnet", "Telecommunicaciones Movilnet"),

            /*****************
             **** Bolivia ****
             *****************/
            new SPNObject(736, 1, "Nuevatel", "Nuevatel PCS De Bolivia SA"),
            new SPNObject(736, 2, "Entel", "Entel SA"),
            new SPNObject(736, 3, "Tigo", "Telefonica Celular De Bolivia S.A"),

            /****************
             **** Guyana ****
             ****************/
            new SPNObject(738, 1, "Digicel", "U-Mobile (Cellular) Inc."),

            /*****************
             **** Ecuador ****
             *****************/
            new SPNObject(740, 0, "Movistar", "Otecel S.A."),
            new SPNObject(740, 1, "Porta", "America Movil"),

            /******************
             **** Paraguay ****
             ******************/
            new SPNObject(744, 1, "VOX", "Hola Paraguay S.A."),
            new SPNObject(744, 2, "Claro", "AMX Paraguay S.A."),
            new SPNObject(744, 4, "Tigo", "Telefonica Celular Del Paraguay S.A. (Telecel)"),
            new SPNObject(744, 5, "Personal", "Nucleo S.A."),

            /*****************
             **** Uruguay ****
             *****************/
            new SPNObject(748, 1, "Ancel", "Ancel"),
            new SPNObject(748, 7, "Movistar", "Telefonica Moviles Uruguay"),
            new SPNObject(748, 10, "Claro", "AM Wireless Uruguay S.A."),

            /*******************
             **** Satellite ****
             *******************/
            new SPNObject(901, 1, "ICO", "ICO Satellite Management"),
            new SPNObject(901, 2, "Sense Communications International",
                    "Sense Communications International"),
            new SPNObject(901, 3, "Iridium", "Iridium"),
            new SPNObject(901, 4, "GlobalStar", "Globalstar"),
            new SPNObject(901, 5, "Thuraya RMSS Network", "Thuraya RMSS Network"),
            new SPNObject(901, 6, "Thuraya Satellite telecommunications Company",
                    "Thuraya Satellite Telecommunications Company"),
            new SPNObject(901, 7, "Ellipso", "Ellipso"),
            new SPNObject(901, 9, "Tele1 Europe", "Tele1 Europe"),
            new SPNObject(901, 10, "ACeS", "ACeS"), new SPNObject(901, 11, "Immarsat", "Immarsat"),

            /*************
             **** Sea ****
             *************/
            new SPNObject(901, 12, "MCP", "Maritime Communications Partner AS"),

            /****************
             **** Ground ****
             ****************/
            new SPNObject(901, 13, "GSM.AQ", "GSM.AQ"),

            /*************
             **** Air ****
             *************/
            new SPNObject(901, 14, "AeroMobile AS", "AeroMobile AS"),
            new SPNObject(901, 15, "OnAir Switzerland Sarl", "OnAir Switzerland Sarl"),

            /*******************
             **** Satellite ****
             *******************/
            new SPNObject(901, 16, "Jasper Systems", "Jasper Systems"),
            new SPNObject(901, 17, "Navitas", "Navitas"),
            new SPNObject(901, 18, "Cingular Wireless", "Cingular Wireless"),
            new SPNObject(901, 19, "Vodafone Malta Maritime", "Vodafone Malta Maritime")
    };

    public static CharSequence getSPNByMCCMNC(Context context, String numeric) {
        if (numeric == null || numeric.length() < 4) {
            throw new IllegalArgumentException(
                    "numeric should be a string and its length should be at least 4");
        }
        SPNObject temp = new SPNObject(Integer.parseInt(numeric.substring(0, 3)),
                Integer.parseInt(numeric.substring(3)), null, null);
        LocaleNamesParser.init(context, "SpnProvider",
                com.android.internal.R.array.origin_carrier_names,
                com.android.internal.R.array.locale_carrier_names);
        for (SPNObject spn : LIST_MCC_MNC_SPN) {
            if (temp.equals(spn)) {
                return LocaleNamesParser.getLocaleName(spn.spn);
            }
        }
        return "";
    }
}
