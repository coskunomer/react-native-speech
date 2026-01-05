import type { StyleProp, TextStyle, TextProps } from 'react-native';
/**
 * Data passed to the `onHighlightedPress` callback
 * Contains the required properties of a highlighted segment:
 * - `start`: The starting index of the segment in the full text
 * - `end`: The ending index of the segment in the full text
 * - `text`: The actual text content of the segment
 */
export type HighlightedSegmentArgs = Required<Omit<TextSegmentProps, 'style' | 'isHighlighted'>>;
/**
 * Defines the properties that specify a segment to be highlighted
 */
export interface HighlightedSegmentProps {
    /**
     * The starting index of the segment in the full text
     */
    start: number;
    /**
     * The ending index of the segment in the full text
     */
    end: number;
    /**
     * Custom styling for this specific highlighted segment
     */
    style?: StyleProp<TextStyle>;
}
export interface TextSegmentProps extends Partial<HighlightedSegmentProps> {
    text: string;
    isHighlighted: boolean;
}
export interface HighlightedTextProps extends TextProps {
    /**
     * The full text content to be displayed.
     */
    text: string;
    /**
     * Base styling applied to all highlighted segments,
     * which can be overridden by segment-specific styles
     */
    highlightedStyle?: StyleProp<TextStyle>;
    /**
     * An array of objects defining the highlighted segments.
     * Each object includes `start` and `end` indices and an optional `style`
     */
    highlights?: Array<HighlightedSegmentProps>;
    /**
     * Callback function invoked when a highlighted segment is pressed.
     * Receives an object with the segment's `start`, `end`, and `text` properties
     */
    onHighlightedPress?: (segment: HighlightedSegmentArgs) => void;
}
//# sourceMappingURL=types.d.ts.map