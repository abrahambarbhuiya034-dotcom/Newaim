/**
 * BitAim v4 — Carrom Pool Aim Assistant
 * New: Manual Board Selection, Autoplay, Live Coin Detection, Fixed Aim Lines
 */

import React, {useState, useEffect, useCallback} from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, Alert,
  Switch, ScrollView, Platform, StatusBar,
  NativeModules, Linking, PermissionsAndroid,
} from 'react-native';
import Slider from '@react-native-community/slider';

const {OverlayModule} = NativeModules;
type ShotMode = 'ALL' | 'DIRECT' | 'AI' | 'GOLDEN' | 'LUCKY';

const SHOT_MODES: {mode: ShotMode; label: string; desc: string}[] = [
  {mode: 'ALL',    label: 'All Lines', desc: 'Striker + coin deflection'},
  {mode: 'DIRECT', label: 'Direct',    desc: 'Direct line only'},
  {mode: 'AI',     label: 'AI Aim',    desc: 'Best AI trajectory'},
  {mode: 'GOLDEN', label: 'Golden',    desc: '1 cushion bounce'},
  {mode: 'LUCKY',  label: 'Lucky',     desc: '2 cushion bounces'},
];

export default function App() {
  const [hasOverlay,       setHasOverlay]       = useState(false);
  const [overlayActive,    setOverlayActive]     = useState(false);
  const [autoDetect,       setAutoDetect]        = useState(false);
  const [strikerMoveable,  setStrikerMoveable]   = useState(true);
  const [snapMode,         setSnapMode]          = useState(false);
  const [manualBoardMode,  setManualBoardMode]   = useState(false);
  const [selectedMode,     setSelectedMode]      = useState<ShotMode>('ALL');
  const [sensitivity,      setSensitivity]       = useState(1.0);
  const [detectThreshold,  setDetectThreshold]   = useState(14);
  const [offsetX,          setOffsetX]           = useState(0);
  const [offsetY,          setOffsetY]           = useState(0);

  useEffect(() => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS).catch(()=>{});
    }
    refreshStatus();
    const t = setInterval(refreshStatus, 2000);
    return () => clearInterval(t);
  }, []);

  const refreshStatus = useCallback(async () => {
    try { setHasOverlay(await OverlayModule.canDrawOverlays()); } catch { setHasOverlay(true); }
    try { setAutoDetect(await OverlayModule.isAutoDetectActive()); } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try { OverlayModule.requestOverlayPermission(); setTimeout(refreshStatus, 1500); }
    catch { Alert.alert('Permission Needed', 'Grant "Display over other apps" in Settings.',
      [{text: 'Open Settings', onPress: () => Linking.openSettings()}]); }
  }, [refreshStatus]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        await OverlayModule.stopOverlay();
        setOverlayActive(false); setAutoDetect(false); setManualBoardMode(false);
      } else {
        await OverlayModule.startOverlay();
        setOverlayActive(true);
        try { OverlayModule.setStrikerMoveable(strikerMoveable); } catch {}
        try { OverlayModule.setSnapMode(snapMode); } catch {}
      }
    } catch (e: any) { Alert.alert('Error', e.message); }
  }, [hasOverlay, overlayActive, strikerMoveable, snapMode, requestOverlay]);

  const toggleAutoDetect = useCallback(async () => {
    if (!overlayActive) {
      Alert.alert('Start Overlay First', 'Turn on the Aim Overlay before enabling auto-detect.');
      return;
    }
    try {
      if (autoDetect) { await OverlayModule.stopScreenCapture(); setAutoDetect(false); }
      else { await OverlayModule.requestScreenCapture(); setTimeout(refreshStatus, 2500); }
    } catch (e: any) { Alert.alert('Error', e.message); }
  }, [overlayActive, autoDetect, refreshStatus]);

  const toggleManualBoard = useCallback((val: boolean) => {
    if (!overlayActive) {
      Alert.alert('Start Overlay First', 'Turn on the overlay, then use manual board mode.');
      return;
    }
    setManualBoardMode(val);
    try { OverlayModule.setManualBoardMode(val); } catch {}
  }, [overlayActive]);

  const clearBoard = useCallback(() => {
    try { OverlayModule.clearManualBoard(); } catch {}
    setManualBoardMode(false);
  }, []);

  const toggleStrikerMoveable = useCallback((val: boolean) => {
    setStrikerMoveable(val); try { OverlayModule.setStrikerMoveable(val); } catch {};
  }, []);

  const toggleSnapMode = useCallback((val: boolean) => {
    setSnapMode(val); try { OverlayModule.setSnapMode(val); } catch {};
  }, []);

  const handleModeSelect = useCallback((mode: ShotMode) => {
    setSelectedMode(mode); try { OverlayModule.setShotMode(mode); } catch {};
  }, []);

  const handleSensitivity = useCallback((val: number) => {
    setSensitivity(val); try { OverlayModule.setSensitivity(val); } catch {};
  }, []);

  const handleThreshold = useCallback((val: number) => {
    setDetectThreshold(val); try { OverlayModule.setDetectionThreshold(val); } catch {};
  }, []);

  const handleOffset = useCallback((axis: 'X'|'Y', val: number) => {
    const nx = axis==='X' ? val : offsetX;
    const ny = axis==='Y' ? val : offsetY;
    if (axis==='X') setOffsetX(val); else setOffsetY(val);
    try { OverlayModule.setMarginOffset(nx, ny); } catch {};
  }, [offsetX, offsetY]);

  return (
    <View style={s.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0D0D1A" />
      <View style={s.header}>
        <Text style={s.logo}>Bit-Aim  v4</Text>
        <Text style={s.sub}>Carrom Aim Assist — Manual Board • Autoplay • Live Detection</Text>
      </View>

      <ScrollView style={s.scroll} contentContainerStyle={s.scrollContent}
        showsVerticalScrollIndicator={false}>

        {/* Permission banner */}
        {!hasOverlay && (
          <TouchableOpacity style={s.permBanner} onPress={requestOverlay}>
            <Text style={s.permText}>Tap to grant "Display over other apps" →</Text>
          </TouchableOpacity>
        )}

        {/* ── Core toggles ── */}
        <View style={s.card}>
          <Row title="Aim Overlay" color="#FFD700"
            sub={overlayActive ? 'Running — tap floating icon in-game' : 'Start aim lines over Carrom Pool'}
            value={overlayActive} onToggle={toggleOverlay} />
          <Sep />
          <Row title="Auto-Detect (Live CV)" color="#00E5FF"
            sub={autoDetect ? '✓ Screen read active — live coin + board detection' : 'Detect board, striker, coins via screen capture'}
            value={autoDetect} onToggle={toggleAutoDetect} />
          <Sep />
          <Row title="Autoplay  🎯" color="#22FF6E"
            sub={snapMode ? '✓ Auto-aims at best coin every frame' : 'Manually tap board to aim'}
            value={snapMode} onToggle={toggleSnapMode} />
          <Sep />
          <Row title="Moveable Striker" color="#FF8A00"
            sub={strikerMoveable ? 'Drag gold-ringed striker to reposition' : 'Striker locked to detected position'}
            value={strikerMoveable} onToggle={toggleStrikerMoveable} />
        </View>

        {/* ── Manual Board Selection ── */}
        <View style={[s.card, manualBoardMode && s.activeCard]}>
          <Text style={s.cardTitle}>Manual Board Selection</Text>
          <Text style={s.cardSub}>
            If auto-detect misses the board — draw it yourself directly on the overlay.
            Drag to outline the board, then drag the 4 corner handles to fine-tune.
          </Text>
          <View style={s.btnRow}>
            <TouchableOpacity
              style={[s.btn, manualBoardMode && s.btnActive]}
              onPress={() => toggleManualBoard(!manualBoardMode)}>
              <Text style={[s.btnTxt, manualBoardMode && s.btnTxtActive]}>
                {manualBoardMode ? '✓ Drawing Mode ON' : 'Set Board Manually'}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity style={s.btnDanger} onPress={clearBoard}>
              <Text style={s.btnDangerTxt}>Clear Board</Text>
            </TouchableOpacity>
          </View>
          {manualBoardMode && (
            <View style={s.infoBox}>
              <Text style={s.infoTxt}>
                {'→ Switch to the game\n→ Drag finger to draw board outline\n→ Use 2 fingers for precise corners\n→ Drag corner handles to adjust\n→ Come back and tap "Drawing Mode ON" to confirm'}
              </Text>
            </View>
          )}
        </View>

        {/* ── Shot Mode ── */}
        <View style={s.card}>
          <Text style={s.cardTitle}>Prediction Lines</Text>
          <Text style={s.cardSub}>Cyan = striker path  •  Orange = coin path  •  Green = pocketed</Text>
          <View style={s.grid}>
            {SHOT_MODES.map(({mode, label, desc}) => (
              <TouchableOpacity key={mode}
                style={[s.shotBtn, selectedMode===mode && s.shotBtnOn]}
                onPress={() => handleModeSelect(mode)}>
                <Text style={[s.shotLabel, selectedMode===mode && s.shotLabelOn]}>{label}</Text>
                <Text style={s.shotDesc}>{desc}</Text>
              </TouchableOpacity>
            ))}
          </View>
          <View style={s.legend}>
            <Dot color="#00E5FF" label="Aim line" />
            <Dot color="#FF8A00" label="Coin path" />
            <Dot color="#22C55E" label="Pocket!" />
            <Dot color="#22FF6E" label="Auto-aim" />
          </View>
        </View>

        {/* ── Shot Power ── */}
        <View style={s.card}>
          <View style={s.rowSp}>
            <Text style={s.cardTitle}>Shot Power</Text>
            <Text style={s.val}>{sensitivity.toFixed(1)}×</Text>
          </View>
          <Slider style={s.slider} minimumValue={0.3} maximumValue={3.0} step={0.1}
            value={sensitivity} onValueChange={handleSensitivity}
            minimumTrackTintColor="#FFD700" maximumTrackTintColor="#333" thumbTintColor="#FFD700" />
          <View style={s.rowSp}><Text style={s.end}>Soft</Text><Text style={s.end}>Hard</Text></View>
        </View>

        {/* ── Detection Sensitivity ── */}
        <View style={s.card}>
          <View style={s.rowSp}>
            <Text style={s.cardTitle}>Detection Sensitivity</Text>
            <Text style={[s.val,{color:'#00E5FF'}]}>{detectThreshold}</Text>
          </View>
          <Text style={s.cardSub}>Lower = detect more circles. Raise if getting false positives.</Text>
          <Slider style={s.slider} minimumValue={5} maximumValue={40} step={1}
            value={detectThreshold} onValueChange={handleThreshold}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333" thumbTintColor="#00E5FF" />
          <View style={s.rowSp}><Text style={s.end}>Sensitive</Text><Text style={s.end}>Strict</Text></View>
        </View>

        {/* ── Fine Tune Offset ── */}
        <View style={s.card}>
          <Text style={s.cardTitle}>Fine-Tune Offset</Text>
          <Text style={s.cardSub}>Nudge aim if there's a small screen offset.</Text>
          <Text style={s.marginLbl}>X: <Text style={s.marginVal}>{offsetX.toFixed(1)}</Text></Text>
          <Slider style={s.slider} minimumValue={-30} maximumValue={30} step={0.5}
            value={offsetX} onValueChange={v => handleOffset('X',v)}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333" thumbTintColor="#00E5FF" />
          <Text style={s.marginLbl}>Y: <Text style={s.marginVal}>{offsetY.toFixed(1)}</Text></Text>
          <Slider style={s.slider} minimumValue={-30} maximumValue={30} step={0.5}
            value={offsetY} onValueChange={v => handleOffset('Y',v)}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333" thumbTintColor="#00E5FF" />
          <TouchableOpacity style={s.resetBtn}
            onPress={() => { setOffsetX(0); setOffsetY(0); try{OverlayModule.setMarginOffset(0,0);}catch{} }}>
            <Text style={s.resetTxt}>Reset Offset</Text>
          </TouchableOpacity>
        </View>

        {/* ── How to Use ── */}
        <View style={s.card}>
          <Text style={s.cardTitle}>How to Use</Text>
          <Step n="1" t='Grant "Draw over apps" permission' />
          <Step n="2" t="Turn on Aim Overlay" />
          <Step n="3" t="Turn on Auto-Detect" />
          <Step n="4" t="Open Carrom Pool" />
          <Step n="5" t="Tap the floating icon to show aim lines" />
          <Step n="6" t="Tap the board to set aim target — a cyan line appears" hi />
          <Step n="7" t="Enable Autoplay — aim auto-locks to best coin each frame" hi />
          <Step n="8" t="If board not detected: use Manual Board Selection above" hi />
          <Step n="9" t="Drag the gold-ringed striker to reposition it" />
        </View>

        <View style={s.footer}>
          <Text style={s.footerTxt}>Bit-Aim v4 — Manual Board • Autoplay • Live Detection</Text>
        </View>
      </ScrollView>
    </View>
  );
}

function Row({title,sub,value,onToggle,color}:{title:string;sub:string;value:boolean;onToggle:(v:boolean)=>void;color:string}) {
  return (
    <View style={s.row}>
      <View style={{flex:1,paddingRight:10}}>
        <Text style={s.cardTitle}>{title}</Text>
        <Text style={s.cardSub}>{sub}</Text>
      </View>
      <Switch value={value} onValueChange={onToggle}
        trackColor={{false:'#333',true:color}} thumbColor={value?'#FFF':'#888'} />
    </View>
  );
}
function Sep() { return <View style={{height:1,backgroundColor:'#222244',marginVertical:12}}/>; }
function Dot({color,label}:{color:string;label:string}) {
  return <View style={s.dotRow}><View style={[s.dot,{backgroundColor:color}]}/><Text style={s.dotLbl}>{label}</Text></View>;
}
function Step({n,t,hi}:{n:string;t:string;hi?:boolean}) {
  return (
    <View style={s.stepRow}>
      <View style={[s.badge,hi&&s.badgeHi]}><Text style={[s.badgeN,hi&&{color:'#22FF6E'}]}>{n}</Text></View>
      <Text style={[s.stepTxt,hi&&{color:'#22FF6E',fontWeight:'700'}]}>{t}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  root:{flex:1,backgroundColor:'#0D0D1A'},
  header:{paddingTop:Platform.OS==='android'?StatusBar.currentHeight??24:44,paddingBottom:16,
    paddingHorizontal:20,backgroundColor:'#13132A',borderBottomWidth:1,borderBottomColor:'#222244'},
  logo:{color:'#FFD700',fontSize:26,fontWeight:'900',letterSpacing:1},
  sub:{color:'#8888BB',fontSize:11,marginTop:2},
  scroll:{flex:1},
  scrollContent:{padding:16,paddingBottom:40},
  permBanner:{backgroundColor:'#2A1A00',borderWidth:1,borderColor:'#FFD700',borderRadius:10,padding:14,marginBottom:12},
  permText:{color:'#FFD700',fontSize:13,fontWeight:'700'},
  card:{backgroundColor:'#16162E',borderRadius:14,padding:16,marginBottom:14,borderWidth:1,borderColor:'#222244'},
  activeCard:{borderColor:'#00E5FF',backgroundColor:'#0A1A2A'},
  cardTitle:{color:'#FFF',fontSize:15,fontWeight:'700',marginBottom:3},
  cardSub:{color:'#8888BB',fontSize:12,marginBottom:4},
  row:{flexDirection:'row',alignItems:'center',justifyContent:'space-between'},
  rowSp:{flexDirection:'row',justifyContent:'space-between',alignItems:'center'},
  btnRow:{flexDirection:'row',gap:10,marginTop:12},
  btn:{flex:1,paddingVertical:10,borderRadius:9,backgroundColor:'#1E1E3A',
    alignItems:'center',borderWidth:1.5,borderColor:'#333355'},
  btnActive:{borderColor:'#00E5FF',backgroundColor:'#00151F'},
  btnTxt:{color:'#8888BB',fontSize:13,fontWeight:'600'},
  btnTxtActive:{color:'#00E5FF'},
  btnDanger:{paddingVertical:10,paddingHorizontal:16,borderRadius:9,
    backgroundColor:'#2A0000',borderWidth:1.5,borderColor:'#FF4444'},
  btnDangerTxt:{color:'#FF7777',fontSize:13,fontWeight:'600'},
  infoBox:{backgroundColor:'#071830',borderRadius:8,padding:12,marginTop:10,
    borderWidth:1,borderColor:'#00E5FF44'},
  infoTxt:{color:'#7BBBFF',fontSize:12,lineHeight:20},
  grid:{flexDirection:'row',flexWrap:'wrap',gap:8,marginTop:8},
  shotBtn:{width:'47%',backgroundColor:'#1E1E3A',borderRadius:10,padding:12,
    borderWidth:1.5,borderColor:'#333355'},
  shotBtnOn:{borderColor:'#00E5FF',backgroundColor:'#00151F'},
  shotLabel:{color:'#AAA',fontSize:14,fontWeight:'700'},
  shotLabelOn:{color:'#00E5FF'},
  shotDesc:{color:'#666688',fontSize:10,marginTop:3},
  legend:{flexDirection:'row',flexWrap:'wrap',gap:10,marginTop:12},
  dotRow:{flexDirection:'row',alignItems:'center'},
  dot:{width:14,height:4,borderRadius:2,marginRight:6},
  dotLbl:{color:'#AAA',fontSize:11},
  slider:{width:'100%',height:36},
  val:{color:'#FFD700',fontSize:16,fontWeight:'700'},
  end:{color:'#666688',fontSize:11},
  marginLbl:{color:'#AAA',fontSize:13,marginBottom:2},
  marginVal:{color:'#00E5FF',fontWeight:'700'},
  resetBtn:{marginTop:8,paddingVertical:8,borderRadius:8,backgroundColor:'#1E1E3A',
    alignItems:'center',borderWidth:1,borderColor:'#444466'},
  resetTxt:{color:'#FF7777',fontSize:13,fontWeight:'600'},
  stepRow:{flexDirection:'row',alignItems:'flex-start',marginBottom:8},
  badge:{width:22,height:22,borderRadius:11,backgroundColor:'#1E1E3A',alignItems:'center',
    justifyContent:'center',marginRight:10,marginTop:1,borderWidth:1,borderColor:'#333355'},
  badgeHi:{borderColor:'#22FF6E'},
  badgeN:{color:'#8888BB',fontSize:11,fontWeight:'700'},
  stepTxt:{color:'#CCCCEE',fontSize:13,flex:1},
  footer:{alignItems:'center',marginTop:10},
  footerTxt:{color:'#444466',fontSize:11},
});
