# Radio+

Radio+ on avoimen lähdekoodin FM/AM-käyttöliittymä yhteensopiville Android-soittimille.
Tämä on ilmainen laitekohtainen beta, ei kaikilla Android-laitteilla toimiva radio.
**Vakioradion `com.hcn.autoradio` ja sen FMPlugService-palvelun täytyy olla
asennettuna ja käytössä.** Sovellus ei korvaa vakioradiota eikä muuta firmwarea.

Radio+ on varmistettu toimivaksi oikealla **Junsun V7** -soittimella ylläpitäjän
omassa testissä. Muu testaus on tehty vain emulaattorissa. Muiden soitinmallien
toimivuutta ei ole varmistettu, ja firmware-erot voivat vaikuttaa myös V7-malleihin.

[Lataa beta](https://github.com/edman01/radio-plus-open/releases)
· [English](../README.md) · [Lähdekoodin kääntäminen](BUILD.md)

## Asennus

1. Varmista Android 8.1 tai uudempi sekä yhteensopiva vakioradion palvelu.
   Pelkkä Junsun V7 -nimi tai Android-versio ei takaa toimintaa.
2. Lataa Releases-sivulta `RadioPlus-community-beta.apk`. Tarkistussumma on saman
   julkaisun `SHA256SUMS.txt`-tiedostossa.
3. Kopioi APK esimerkiksi USB-tikulle ja avaa se soittimen tiedostonhallinnassa.
   Salli tarvittaessa asennus tästä lähteestä, jos luotat julkaisuun.
4. Säilytä vakioradio asennettuna. Avaa Radio+. **Viritys / Tuning** avaa
   automaattihaun tai manuaalisen virityksen. **Asemalista / Stations** näyttää asemat.
5. Vaihda halutessasi kieli: **Settings → General → Language → Suomi**.

Julkisen version sovellustunnus on `fi.radioplus.app.play`. Nimestä huolimatta
kyseessä ei ole Google Play -julkaisu. Versio asentuu vanhan henkilökohtaisen
Radio+:n rinnalle, eikä sen tietoja siirretä automaattisesti. Päivitä tämä versio
jatkossa saman julkaisusarjan APK:lla poistamatta sovellusta tai sen tietoja.

Tässä GitHub-betassa ei ole kokeilurajaa, maksuja tai mainoksia.

## Omien logojen tuonti USB-tikulta

1. Hanki PNG- tai JPG-kuvat lähteestä, jonka käyttöehdot sallivat käyttötarkoituksesi.
   Pura mahdollinen ZIP/RAR-paketti ensin. Sovellus valitsee yksittäisiä kuvia,
   ei kokonaisia logopaketteja.
2. Kopioi kuvat tikulle ja liitä tikku soittimeen.
3. Paina kanavakorttia pitkään → **Vaihda logo** → **Lisää oma logo laitteelta…**.
4. Valitse Androidin tiedostonvalitsimesta USB-tikku ja kanavan kuva.
5. Toista haluamillesi kanaville. Logot eivät yhdisty kanaviin automaattisesti.

**160×120 pikselin kuvat toimivat.** Tarkempi alkuperäinen, esimerkiksi 400×240 tai
500×500, voi näyttää paremmalta suurella näytöllä. Kuvasuhde säilyy; yli 512 pikselin
pitkä sivu pienennetään 512:een, eikä pieniä kuvia suurenneta tuonnissa. Pieni kuva
voi siksi näkyä kortissa pienempänä. Tuonti ei palauta puuttuvia yksityiskohtia.

Sovellus tallentaa kuvasta oman kopion: tikun voi irrottaa onnistuneen tuonnin
jälkeen. **Vaihda logo → Poista logo** poistaa kanavan logon sovelluksesta, ei
alkuperäistä tiedostoa tikulta. Kanavan nimi tai taajuus säilyy.

Škodan virallisilta lataussivuilta saadut tavalliset PNG/JPG-logot voivat toimia
samalla tavalla, mutta lataussivun ehdot pitää tarkistaa. Tekninen yhteensopivuus
ei ole käyttö- tai uudelleenjakelulupa. APK ei sisällä kanavalogoja.

## Suosikit ja kanavat

Suosikkeja hallitaan vain kanavan pitkän painalluksen valikosta. Sieltä voi lisätä
tai poistaa suosikin, nimetä kanavan uudelleen, vaihtaa logon tai järjestää listaa.
Suosikit ja koko asemalista ovat erillisiä. RDS-nimi näkyy vain, jos laite toimittaa
käyttökelpoisen nimitiedon; voit nimetä kanavan itse.

## Rajoitukset

FM-ääntä, mykistystä, AM-viritystä, YouTubesta radioon vaihtamista, rattipainikkeita
ja ACC-heräämistä ei voi todistaa emulaattorilla. Toiminta riippuu soittimen
firmwaresta. Valinnainen rattipainikkeiden saavutettavuuspalvelu käsittelee
tuettuja näppäimiä vain Radio+:n ollessa näkyvissä. Se ei ohita valmistajan
estoja, eikä sitä tarvitse ottaa käyttöön, jos tavallinen mediaohjaus toimii.

Demokuvan kaikki kanavalogot ovat havainnollistavaa esikatselua, eivät ladattavan
version sisältöä. Tämä ei ole Junsunin tai Škodan hyväksymä sovellus.
Tee asetukset auton ollessa pysäköitynä.

[Testauksen rajaus](TESTING.md) · [Tietosuoja](PRIVACY.md) · [MIT](../LICENSE)
