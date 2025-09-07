import React from 'react';
import {gs} from '../styles/gs';
import Button from '../components/Button';
import {Alert, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Speech, {
  HighlightedText,
  type HighlightedSegmentArgs,
  type HighlightedSegmentProps,
} from '@mhpdev/react-native-speech';

const Introduction =
  "This high-performance text-to-speech library is built for bare React Native and Expo, compatible with Android and iOS's new architecture (default from React Native 0.76). It enables seamless speech management with start, pause, resume, and stop controls, and provides events for detailed synthesis management.";

const RootView: React.FC = () => {
  const [isPaused, setIsPaused] = React.useState<boolean>(false);

  const [isStarted, setIsStarted] = React.useState<boolean>(false);

  const [highlights, setHighlights] = React.useState<
    Array<HighlightedSegmentProps>
  >([]);

  React.useEffect(() => {
    // Speech.initialize({silentMode: 'ignore'});

    const onSpeechEnd = () => {
      setIsStarted(false);
      setIsPaused(false);
      setHighlights([]);
    };

    const startSubscription = Speech.onStart(({id}) => {
      console.log(`Speech ${id} started`);
    });
    const finishSubscription = Speech.onFinish(({id}) => {
      onSpeechEnd();
      console.log(`Speech ${id} finished`);
    });
    const pauseSubscription = Speech.onPause(({id}) => {
      setIsPaused(true);
      console.log(`Speech ${id} paused`);
    });
    const resumeSubscription = Speech.onResume(({id}) => {
      console.log(`Speech ${id} resumed`);
    });
    const stoppedSubscription = Speech.onStopped(({id}) => {
      onSpeechEnd();
      console.log(`Speech ${id} stopped`);
    });
    const progressSubscription = Speech.onProgress(({id, location, length}) => {
      setHighlights([
        {
          start: location,
          end: location + length,
        },
      ]);
      console.log(
        `Speech ${id} progress, current word length: ${length}, current char position: ${location}`,
      );
    });

    // (async () => {
    //   const enVoices = await Speech.getAvailableVoices('en-us');
    //   Speech.initialize({
    //     rate: 0.5,
    //     volume: 1,
    //     voice: enVoices[3]?.identifier,
    //   });
    // })();

    return () => {
      startSubscription.remove();
      finishSubscription.remove();
      pauseSubscription.remove();
      resumeSubscription.remove();
      stoppedSubscription.remove();
      progressSubscription.remove();
    };
  }, []);

  const onStartPress = React.useCallback(() => {
    setIsStarted(true);
    Speech.speak(Introduction);
  }, []);

  const onResumePress = React.useCallback(() => {
    setIsPaused(false);
    Speech.resume();
  }, []);

  const onHighlightedPress = React.useCallback(
    ({text, start, end}: HighlightedSegmentArgs) =>
      Alert.alert(
        'Highlighted',
        `The current segment is "${text}", starting at ${start} and ending at ${end}`,
      ),
    [],
  );

  return (
    <SafeAreaView style={[gs.flex, gs.p10]}>
      <View style={gs.flex}>
        <Text style={gs.title}>Introduction</Text>
        <HighlightedText
          text={Introduction}
          style={gs.paragraph}
          highlights={highlights}
          highlightedStyle={styles.highlighted}
          onHighlightedPress={onHighlightedPress}
        />
      </View>
      <View style={[gs.row, gs.p10]}>
        <Button label="Start" disabled={isStarted} onPress={onStartPress} />
        <Button label="Stop" disabled={!isStarted} onPress={Speech.stop} />
        <Button
          label="Pause"
          onPress={Speech.pause}
          disabled={isPaused || !isStarted}
        />
        <Button label="Resume" disabled={!isPaused} onPress={onResumePress} />
      </View>
    </SafeAreaView>
  );
};

export default RootView;

const styles = StyleSheet.create({
  highlighted: {
    fontWeight: '600',
    backgroundColor: '#ffff00',
  },
});
