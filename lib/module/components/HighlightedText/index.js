"use strict";

import React, { useMemo, useCallback } from 'react';
import { Text, StyleSheet, TouchableWithoutFeedback } from 'react-native';
import { jsx as _jsx } from "react/jsx-runtime";
const HighlightedText = ({
  text,
  style,
  highlights = [],
  highlightedStyle,
  onHighlightedPress,
  ...rest
}) => {
  const segments = useMemo(() => {
    const sorted = [...highlights].sort((a, b) => a.start - b.start);
    const parts = [];
    let currentIndex = 0;
    for (let i = 0; i < sorted.length; ++i) {
      const currentSegment = sorted[i];
      const {
        start,
        end
      } = currentSegment;
      if (start > currentIndex) {
        parts.push({
          isHighlighted: false,
          text: text.slice(currentIndex, start)
        });
      }
      parts.push({
        ...currentSegment,
        isHighlighted: true,
        text: text.slice(start, end)
      });
      currentIndex = end;
    }
    if (currentIndex < text.length) {
      parts.push({
        isHighlighted: false,
        text: text.slice(currentIndex)
      });
    }
    return parts;
  }, [highlights, text]);
  const getHighlightedSegmentStyle = useCallback((isHighlighted = false, segmentStyle) => {
    return !isHighlighted ? style : StyleSheet.flatten([style, highlightedStyle ?? styles.isHighlighted, segmentStyle]);
  }, [highlightedStyle, style]);
  const renderText = useCallback((segment, index) => {
    return !segment.isHighlighted ? /*#__PURE__*/_jsx(Text, {
      style: getHighlightedSegmentStyle(segment.isHighlighted),
      children: segment.text
    }, index) : /*#__PURE__*/_jsx(TouchableWithoutFeedback, {
      onPress: () => onHighlightedPress?.({
        end: segment.end,
        text: segment.text,
        start: segment.start
      }),
      children: /*#__PURE__*/_jsx(Text, {
        style: getHighlightedSegmentStyle(segment.isHighlighted, segment.style),
        children: segment.text
      })
    }, index);
  }, [getHighlightedSegmentStyle, onHighlightedPress]);
  return /*#__PURE__*/_jsx(Text, {
    style: style,
    ...rest,
    children: segments.map(renderText)
  });
};
export default HighlightedText;
const styles = StyleSheet.create({
  isHighlighted: {
    backgroundColor: '#FFFF00'
  }
});
//# sourceMappingURL=index.js.map