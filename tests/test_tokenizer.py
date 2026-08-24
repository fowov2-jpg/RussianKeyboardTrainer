from rsk_trainer.tokenizer import CharTokenizer

def test_pair_shape():
    t=CharTokenizer.default(); ids,mask=t.encode_pair("Вася", "кушачет", "кушает", 64)
    assert len(ids)==64 and len(mask)==64 and mask[0]==1
