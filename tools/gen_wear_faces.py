"""Generates the three Watch Face Format watch faces (Material 3 Expressive look): manifests, metadata, strings, watchface.xml.
Run: python gen_faces.py <outdir>.  Images (blobs, previews) come from the Robolectric FaceAssetsTest."""
import os, sys

OUT = sys.argv[1]
IRIS = "#ffc3bbff"; WHITE = "#ffffffff"; GREY = "#ffb9b5c6"; AMBER = "#ffffb86b"; INK_AMBER = "#ff4a2800"; CONT = "#ff3a2fb0"; SEC = "#ff45435a"; ON_SEC = "#ffe6e3f2"; AMBER_C = "#ff6a3d00"
PKG = "com.past9.phoneaos"
def prov(cls): return f"{PKG}/{PKG}.wear.{cls}"

def time_text(fmt, x, y, w, h, size, weight, color, ambient_color=GREY, ambient_weight="NORMAL", align="CENTER"):
    """A time or date text with an interactive look and a lighter ambient one."""
    return f'''    <DigitalClock x="0" y="0" width="450" height="450">
      <TimeText format="{fmt}" hourFormat="SYNC_TO_DEVICE" align="{align}" x="{x}" y="{y}" width="{w}" height="{h}" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="SYNC_TO_DEVICE" size="{size}" weight="{weight}" slant="NORMAL" color="{color}"/>
      </TimeText>
      <TimeText format="{fmt}" hourFormat="SYNC_TO_DEVICE" align="{align}" x="{x}" y="{y}" width="{w}" height="{h}" alpha="0">
        <Variant mode="AMBIENT" target="alpha" value="255"/>
        <Font family="SYNC_TO_DEVICE" size="{size}" weight="{ambient_weight}" slant="NORMAL" color="{ambient_color}"/>
      </TimeText>
    </DigitalClock>
'''

def date_text(x, y, w, h, size, color, month=True, ambient_color=GREY):
    """The date as text built from the date expressions (a TimeText takes only time formats)."""
    tmpl = '%s %s %s<Parameter expression="[DAY_OF_WEEK_S]"/><Parameter expression="[DAY_OF_MONTH]"/><Parameter expression="[MONTH_S]"/>' if month else '%s %s<Parameter expression="[DAY_OF_WEEK_S]"/><Parameter expression="[DAY_OF_MONTH]"/>'
    def one(a, c, wt):
        return f'''    <PartText x="{x}" y="{y}" width="{w}" height="{h}" alpha="{255 if a else 0}">
      <Variant mode="AMBIENT" target="alpha" value="{0 if a else 255}"/>
      <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="{size}" weight="{wt}" color="{c}"><Template>{tmpl}</Template></Font></Text>
    </PartText>
'''
    return one(True, color, "BOLD") + one(False, ambient_color, "NORMAL")

def image(res, x, y, w, h, ambient_res=None):
    s = f'''    <PartImage x="{x}" y="{y}" width="{w}" height="{h}" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Image resource="{res}"/>
    </PartImage>
'''
    if ambient_res:
        s += f'''    <PartImage x="{x}" y="{y}" width="{w}" height="{h}" alpha="0">
      <Variant mode="AMBIENT" target="alpha" value="255"/>
      <Image resource="{ambient_res}"/>
    </PartImage>
'''
    return s

def mascot_slot(slot, x, y, w, h):
    """The agent: its own complication, so it is the mascot the user picked, in the accent, amber when something needs them. Tap: talk."""
    return f'''    <ComplicationSlot slotId="{slot}" x="{x}" y="{y}" width="{w}" height="{h}" displayName="mascot_slot" supportedTypes="SMALL_IMAGE PHOTO_IMAGE EMPTY">
      <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="{prov('MascotComplication')}" primaryProviderType="SMALL_IMAGE"/>
      <BoundingOval x="0" y="0" width="{w}" height="{h}" outlinePadding="2"/>
      <Complication type="SMALL_IMAGE">
        <PartImage x="0" y="0" width="{w}" height="{h}">
          <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
        </PartImage>
      </Complication>
      <Complication type="PHOTO_IMAGE">
        <PartImage x="0" y="0" width="{w}" height="{h}">
          <Image resource="[COMPLICATION.PHOTO_IMAGE]"/>
        </PartImage>
      </Complication>
    </ComplicationSlot>
'''

def short_slot(slot, name, provider, x, y, w, h, fill=None, color=WHITE, size=44, ambient_color=GREY):
    bg = ""
    if fill:
        bg = f'''        <PartDraw x="0" y="0" width="{w}" height="{h}" alpha="255">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Ellipse x="0" y="0" width="{w}" height="{h}"><Fill color="{fill}"/></Ellipse>
        </PartDraw>
'''
    return f'''    <ComplicationSlot slotId="{slot}" x="{x}" y="{y}" width="{w}" height="{h}" displayName="{name}" supportedTypes="SHORT_TEXT MONOCHROMATIC_IMAGE EMPTY">
      <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="{prov(provider)}" primaryProviderType="SHORT_TEXT"/>
      <BoundingOval x="0" y="0" width="{w}" height="{h}" outlinePadding="2"/>
      <Complication type="SHORT_TEXT">
{bg}        <PartText x="0" y="{h//2 - size//2 - 4}" width="{w}" height="{size + 8}" alpha="255">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="{size}" weight="BOLD" color="{color}"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
        </PartText>
        <PartText x="0" y="{h//2 - size//2 - 4}" width="{w}" height="{size + 8}" alpha="0">
          <Variant mode="AMBIENT" target="alpha" value="255"/>
          <Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="{size}" weight="NORMAL" color="{ambient_color}"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
        </PartText>
      </Complication>
      <Complication type="MONOCHROMATIC_IMAGE">
        <PartImage x="{w//4}" y="{h//4}" width="{w//2}" height="{h//2}" tintColor="{color}">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>
      </Complication>
    </ComplicationSlot>
'''

def long_slot(slot, x, y, w, h):
    return f'''    <ComplicationSlot slotId="{slot}" x="{x}" y="{y}" width="{w}" height="{h}" displayName="task_slot" supportedTypes="LONG_TEXT SHORT_TEXT EMPTY">
      <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="{prov('NextTaskComplication')}" primaryProviderType="LONG_TEXT"/>
      <BoundingRoundBox x="0" y="0" width="{w}" height="{h}"/>
      <Complication type="LONG_TEXT">
        <PartDraw x="0" y="0" width="{w}" height="{h}" alpha="255">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <RoundRectangle x="0" y="0" width="{w}" height="{h}" cornerRadiusX="{h//2}" cornerRadiusY="{h//2}"><Fill color="{SEC}"/></RoundRectangle>
        </PartDraw>
        <PartText x="{h//2}" y="{h//2 - 18}" width="{w - h}" height="36" alpha="255">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="28" weight="BOLD" color="{ON_SEC}"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
        </PartText>
        <PartText x="{h//2}" y="{h//2 - 18}" width="{w - h}" height="36" alpha="0">
          <Variant mode="AMBIENT" target="alpha" value="255"/>
          <Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="28" weight="NORMAL" color="{GREY}"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
        </PartText>
      </Complication>
      <Complication type="SHORT_TEXT">
        <PartText x="0" y="{h//2 - 18}" width="{w}" height="36">
          <Text align="CENTER" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="28" weight="BOLD" color="{ON_SEC}"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
        </PartText>
      </Complication>
    </ComplicationSlot>
'''

def face(scene_body, preview_time="10:08:32"):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by gen_faces.py: the Agent's watch face in Material 3 Expressive. Images are drawn by FaceAssetsTest; the agent itself is a complication (MascotComplication in the Agent watch app) so it is the mascot and accent the user picked. -->
<WatchFace width="450" height="450">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="{preview_time}"/>
  <Scene backgroundColor="#ff000000">
{scene_body}  </Scene>
</WatchFace>
'''

# 1. Still: stacked bold clock, the agent beside the minutes, the next task along the bottom
still = (
    date_text(0, 22, 450, 36, 28, IRIS)
    + time_text("hh", 0, 52, 450, 180, 170, "BOLD", WHITE, ambient_weight="NORMAL")
    + time_text("mm", 0, 205, 450, 180, 170, "BOLD", IRIS, ambient_weight="NORMAL")
    + image("blob9_container", 322, 215, 118, 118, "blob9_outline")
    + mascot_slot(0, 332, 225, 98, 98)
    + long_slot(1, 95, 392, 260, 44)
)
# 2. Orb: the agent is the dial, big, on a scalloped disc that bleeds off the bottom; the badge on its shoulder
orb = (
    time_text("hh:mm", 0, 14, 450, 120, 112, "BOLD", WHITE, ambient_weight="NORMAL")
    + date_text(0, 126, 450, 36, 26, IRIS, month=False)
    + image("blob12_container", 12, 178, 426, 426 - 154, "blob12_outline")
    + mascot_slot(0, 105, 205, 240, 240)
    + short_slot(1, "needs_slot", "PendingComplication", 322, 170, 84, 84, fill=AMBER, color=INK_AMBER, size=44)
)
# 3. Data: three shape containers (tasks, the agent, needs you) and the next task as a pill
data = (
    time_text("hh:mm", 0, 16, 450, 120, 112, "BOLD", WHITE, ambient_weight="NORMAL")
    + date_text(0, 128, 450, 32, 24, IRIS)
    + image("blob6_container", 42, 204, 118, 118, "blob6_outline")
    + image("blob9_secc", 160, 196, 130, 130, "blob9_outline")
    + image("flower_amber", 290, 204, 118, 118, "flower_outline")
    + short_slot(1, "status_slot", "StatusComplication", 50, 212, 102, 102, color=WHITE, size=34)
    + mascot_slot(0, 170, 206, 110, 110)
    + short_slot(2, "needs_slot", "PendingComplication", 298, 212, 102, 102, color="#ffffddb8", size=48)
    + long_slot(3, 80, 350, 290, 52)
)

faces = {
    "still": ("Agent Still", still),
    "orb": ("Agent Orb", orb),
    "data": ("Agent Data", data),
}

for key, (label, body) in faces.items():
    d = os.path.join(OUT, key)
    for sub in ("src/main/res/raw", "src/main/res/xml", "src/main/res/values", "src/main/res/drawable-nodpi"):
        os.makedirs(os.path.join(d, sub), exist_ok=True)
    open(os.path.join(d, "src/main/res/raw/watchface.xml"), "w", encoding="utf8", newline="\n").write(face(body))
    open(os.path.join(d, "src/main/res/xml/watch_face_info.xml"), "w", encoding="utf8", newline="\n").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<WatchFaceInfo>\n  <Preview value="@drawable/preview" />\n  <Editable value="true" />\n  <AvailableInRetail value="true" />\n</WatchFaceInfo>\n')
    open(os.path.join(d, "src/main/res/values/strings.xml"), "w", encoding="utf8", newline="\n").write(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="watch_face_name">{label}</string>\n</resources>\n')
    open(os.path.join(d, "src/main/AndroidManifest.xml"), "w", encoding="utf8", newline="\n").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n\n'
        '    <uses-feature android:name="android.hardware.type.watch" />\n\n'
        '    <!-- A Watch Face Format face is resources only: no code (hasCode false is required). The agent, the next task, the approvals and the status come from the Agent watch app\'s complication data sources. -->\n'
        '    <application android:label="@string/watch_face_name" android:hasCode="false">\n'
        '        <meta-data android:name="com.google.android.wearable.standalone" android:value="true" />\n'
        '        <property android:name="com.google.wear.watchface.format.version" android:value="2" />\n'
        '    </application>\n</manifest>\n')
    gradle = """plugins { alias(libs.plugins.android.application) }

// Release signing lives OUTSIDE the repo, as for the phone app: ~/phone-aos/keys/signing.properties, or -PsigningProps=<file>.
val releaseSigning: Map<String, String> = file((project.findProperty("signingProps") as String?) ?: "${System.getProperty("user.home")}/phone-aos/keys/signing.properties")
  .takeIf { it.exists() }?.readLines()?.filter { "=" in it && !it.startsWith("#") }?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() } ?: emptyMap()

// A Watch Face Format watch face (docs/wear.md, "Watch faces"): resources only. One face per package, so there are three.
android {
  namespace = "com.past9.phoneaos.face.KEY"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.past9.phoneaos.face.KEY"
    minSdk = 34
    targetSdk = 36
    versionCode = (project.findProperty("faceVersionCode") as String?)?.toInt() ?: 900001
    versionName = (project.findProperty("faceVersionName") as String?) ?: "0.1.0"
  }
  signingConfigs {
    if (releaseSigning.isNotEmpty()) create("release") { storeFile = file(releaseSigning.getValue("storeFile")); storePassword = releaseSigning["storePassword"]; keyAlias = releaseSigning["keyAlias"]; keyPassword = releaseSigning["keyPassword"] }
  }
  buildTypes {
    release {
      val wanted = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
      val release = signingConfigs.findByName("release")
      if (release != null) signingConfig = release
      else if (wanted) throw GradleException("No release key. Pass -PsigningProps=<file> (storeFile, storePassword, keyAlias, keyPassword).")
    }
  }
  buildFeatures { buildConfig = false }
  lint { disable += "ExpiredTargetSdkVersion" }
}
""".replace("KEY", key)
    open(os.path.join(d, "build.gradle.kts"), "w", encoding="utf8", newline="\n").write(gradle)
print("faces generated in", OUT)
