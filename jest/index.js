const mockFn = (...args) => {
  return undefined;
};

const mockPromise = (...args) => Promise.resolve(undefined);
const mockPromiseBool = (...args) => Promise.resolve(false);
const mockPromiseArr = (...args) => Promise.resolve([]);

const {fn: jestFn} = require('jest-mock');
const eventSub = {remove: jestFn()};
const eventMock = jestFn(() => eventSub);

class Speech {
  static reset = mockFn;
  static stop = mockPromise;
  static initialize = mockFn;
  static speak = mockPromise;
  static pause = mockPromiseBool;
  static resume = mockPromiseBool;
  static isSpeaking = mockPromiseBool;
  static speakWithOptions = mockPromise;
  static getAvailableVoices = mockPromiseArr;
  static getEngines = mockPromiseArr;
  static setEngine = mockPromise;
  static onError = eventMock;
  static onStart = eventMock;
  static onFinish = eventMock;
  static onPause = eventMock;
  static onResume = eventMock;
  static onStopped = eventMock;
  static onProgress = eventMock;
}

const HighlightedText = jestFn(function HighlightedText() {
  return null;
});

module.exports = {
  __esModule: true,
  default: Speech,
  HighlightedText,
};
