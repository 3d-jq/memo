import 'package:flutter_test/flutter_test.dart';

import 'package:Kelivo/core/models/assistant.dart';

void main() {
  group('Assistant memory fields (§4.1)', () {
    test('defaults match the design doc', () {
      const a = Assistant(id: 'a', name: 'A');
      expect(a.enableMemory, isFalse);
      expect(a.autoOrganizeMemory, isFalse);
      expect(a.memoryOrganizeEveryNTurns, 1);
      expect(a.memorySmartAddMode, MemorySmartAddMode.batched);
      expect(a.memoryWriteScope, MemoryWriteScope.alwaysGlobal);
      expect(a.allowPastConversationRecall, isFalse);
      expect(a.generateConversationSummary, isFalse);
      expect(a.appendCurrentTimeToUserMessage, isFalse);
    });

    test('fromJson maps legacy enableRecentChatsReference', () {
      final a = Assistant.fromJson({
        'id': 'a',
        'name': 'A',
        'enableRecentChatsReference': true,
      });
      expect(a.allowPastConversationRecall, isTrue);
      expect(a.toJson().containsKey('enableRecentChatsReference'), isFalse);
      expect(a.toJson()['allowPastConversationRecall'], isTrue);
    });

    test('explicit allowPastConversationRecall wins over legacy key', () {
      final a = Assistant.fromJson({
        'id': 'a',
        'name': 'A',
        'allowPastConversationRecall': false,
        'enableRecentChatsReference': true,
      });
      expect(a.allowPastConversationRecall, isFalse);
    });

    test('enum wire strings round-trip', () {
      final a = Assistant(
        id: 'a',
        name: 'A',
        memorySmartAddMode: MemorySmartAddMode.perItem,
        memoryWriteScope: MemoryWriteScope.toolDefaultAssistant,
      );
      final json = a.toJson();
      expect(json['memorySmartAddMode'], 'perItem');
      expect(json['memoryWriteScope'], 'toolDefaultAssistant');
      final roundTrip = Assistant.fromJson(json);
      expect(roundTrip.memorySmartAddMode, MemorySmartAddMode.perItem);
      expect(roundTrip.memoryWriteScope, MemoryWriteScope.toolDefaultAssistant);
    });
  });
}
