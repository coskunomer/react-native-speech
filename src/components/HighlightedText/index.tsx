import React, {useMemo, useCallback} from 'react';
import {
  Text,
  StyleSheet,
  type StyleProp,
  type TextStyle,
  TouchableWithoutFeedback,
} from 'react-native';
import type {
  TextSegmentProps,
  HighlightedTextProps,
  HighlightedSegmentProps,
} from '../types';

const HighlightedText: React.FC<HighlightedTextProps> = ({
  text,
  style,
  highlights = [],
  highlightedStyle,
  onHighlightedPress,
  ...rest
}) => {
  const segments = useMemo(() => {
    const sorted = [...highlights].sort((a, b) => a.start - b.start);

    const parts: TextSegmentProps[] = [];
    let currentIndex = 0;

    for (let i = 0; i < sorted.length; ++i) {
      const currentSegment = sorted[i] as HighlightedSegmentProps;
      const {start, end} = currentSegment;

      if (start > currentIndex) {
        parts.push({
          isHighlighted: false,
          text: text.slice(currentIndex, start),
        });
      }
      parts.push({
        ...currentSegment,
        isHighlighted: true,
        text: text.slice(start, end),
      });
      currentIndex = end;
    }
    if (currentIndex < text.length) {
      parts.push({
        isHighlighted: false,
        text: text.slice(currentIndex),
      });
    }
    return parts;
  }, [highlights, text]);

  const getHighlightedSegmentStyle = useCallback(
    (isHighlighted = false, segmentStyle?: StyleProp<TextStyle>) => {
      return !isHighlighted
        ? style
        : StyleSheet.flatten([
            style,
            highlightedStyle ?? styles.isHighlighted,
            segmentStyle,
          ]);
    },
    [highlightedStyle, style],
  );

  const renderText = useCallback(
    (segment: TextSegmentProps, index: number) => {
      return !segment.isHighlighted ? (
        <Text
          key={index}
          style={getHighlightedSegmentStyle(segment.isHighlighted)}>
          {segment.text}
        </Text>
      ) : (
        <TouchableWithoutFeedback
          key={index}
          onPress={() =>
            onHighlightedPress?.({
              end: segment.end!,
              text: segment.text,
              start: segment.start!,
            })
          }>
          <Text
            style={getHighlightedSegmentStyle(
              segment.isHighlighted,
              segment.style,
            )}>
            {segment.text}
          </Text>
        </TouchableWithoutFeedback>
      );
    },
    [getHighlightedSegmentStyle, onHighlightedPress],
  );

  return (
    <Text style={style} {...rest}>
      {segments.map(renderText)}
    </Text>
  );
};

export default HighlightedText;

const styles = StyleSheet.create({
  isHighlighted: {
    backgroundColor: '#FFFF00',
  },
});
