{ lib
, stdenvNoCC
, makeWrapper
, babashka-unwrapped
, fre
}:

stdenvNoCC.mkDerivation {
  pname = "fre-pluggo";
  version = "0.1.0";
  src = ./.;

  nativeBuildInputs = [ makeWrapper ];
  nativeCheckInputs = [ babashka-unwrapped ];

  doCheck = true;
  checkPhase = ''
    runHook preCheck
    HOME=$TMPDIR bb test
    runHook postCheck
  '';

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/fre-pluggo $out/bin
    cp -r src $out/share/fre-pluggo/
    makeWrapper ${babashka-unwrapped}/bin/bb $out/bin/fre-pluggo \
      --add-flags "--classpath $out/share/fre-pluggo/src --main plugbench.fre-pluggo.main" \
      --prefix PATH : ${lib.makeBinPath [ fre ]}

    runHook postInstall
  '';

  meta = {
    description = "A frecency store for plugbench, wrapping fre";
    license = lib.licenses.unlicense;
    mainProgram = "fre-pluggo";
  };
}
