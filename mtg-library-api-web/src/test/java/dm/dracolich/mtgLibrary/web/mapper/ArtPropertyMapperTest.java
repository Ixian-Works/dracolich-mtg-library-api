package dm.dracolich.mtgLibrary.web.mapper;

import dm.dracolich.mtgLibrary.dto.ArtPropertyDto;
import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtPropertyMapperTest {

    @Test
    void theArtistsNameReachesTheResponse() {
        ArtPropertyMapper mapper = Mappers.getMapper(ArtPropertyMapper.class);

        ArtPropertyDto dto = mapper.entityToDto(ArtPropertyEntity.builder()
                .artist("Matt Stewart").faceArtists(List.of("Matt Stewart", "Nils Hamm")).build());

        assertThat(dto.getArtist()).isEqualTo("Matt Stewart");
        assertThat(dto.getFaceArtists()).containsExactly("Matt Stewart", "Nils Hamm");
    }
}
